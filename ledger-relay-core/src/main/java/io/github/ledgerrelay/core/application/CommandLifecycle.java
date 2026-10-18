package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.HaltReason;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.ReservationNotFoundException;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.CommandStateMachine;
import io.github.ledgerrelay.core.domain.ContentHash;
import io.github.ledgerrelay.core.domain.GateDecision;
import io.github.ledgerrelay.core.domain.OrderingGate;
import io.github.ledgerrelay.core.domain.Step;
import io.github.ledgerrelay.core.domain.StepResult;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 명령의 일생을 맡는 서비스. 업무 판단은 모두 여기와 domain에서 한다. 저장소는 저장만 한다.
 *
 * <p>트랜잭션마다 잡는 행(커밋까지 유지):
 * <ul>
 *   <li>{@link #submit}(앱 트랜잭션): 순서 키 행 → 새 명령 행. 앱은 그 앞뒤로 주문 행을 잡는다.
 *   <li>{@link #claim}: 끝난 명령이 맨 앞을 쥔 키 행(쥔 쪽이 있으면 건너뜀) → 후보 명령 행(건너뜀). 핸들러는 돌지 않는다.
 *   <li>워커 쓰기({@link WorkerWrites}): 그 명령 행 → 핸들러가 잡는 주문 행. 순서 키 행은 잡지 않는다.
 * </ul>
 * 순서 키 행과 주문 행을 둘 다 잡는 트랜잭션은 앱의 접수뿐이므로 둘을 반대 순서로 잡아 교착할 상대가 없다.
 */
public final class CommandLifecycle implements LedgerRelay {

    private final OrderingKeys keys;
    private final CommandStore store;
    private final Transactions transactions;
    private final TransferIdGenerator transferIds;
    private final WorkerWrites workerWrites;

    public CommandLifecycle(OrderingKeys keys, CommandStore store, Transactions transactions,
            TransferIdGenerator transferIds, LedgerOutcomeHandler handler, Backoff backoff) {
        this.keys = keys;
        this.store = store;
        this.transactions = transactions;
        this.transferIds = transferIds;
        this.workerWrites = new WorkerWrites(store, transactions, handler, backoff);
    }

    @Override
    public CommandReceipt submit(LedgerCommand command) {
        transactions.requireActive(); // G1: 앱 트랜잭션에 묶인다
        // 키를 잠그고 순번을 받는다. 같은 키의 동시 접수는 여기서 줄을 서므로 순번 순서가 커밋 순서와 같다
        long seq = keys.lockKeyAndTakeSeq(command.orderingKey());
        // 같은 commandId가 있으면 내용을 견준다. 참조 검사보다 먼저 한다. 보관 작업이 예약을 지운 뒤 같은
        // 확정을 다시 보내도 처음 접수증을 돌려줘야 하기 때문이다(F28)
        String hash = ContentHash.of(command);
        Optional<CommandRecord> existing = store.find(command.commandId());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), hash);
        }
        // 참조 검사. 예약의 이체 ID는 영구히 바뀌지 않으므로 지금 정해 둔다
        Optional<BigInteger> pendingTransferId = command.reservation()
                .map(reservationId -> reservationTransferId(command, reservationId));
        // 이체 ID를 내고 넣는다. 그사이 다른 트랜잭션이 같은 commandId를 넣었다면 저장소가 그 행을 돌려준다
        BigInteger transferId = transferIds.next();
        NewCommand row = new NewCommand(command.commandId(), command.orderingKey(), seq, command.reservation(),
                transferId, hash, TransferBody.of(command, pendingTransferId));
        return store.insertIfAbsent(row)
                .map(raced -> sameOrConflict(raced, hash))
                .orElseGet(() -> new CommandReceipt(command.commandId(), transferId));
    }

    @Override
    public Optional<CommandStatus> status(String commandId) {
        if (!LedgerCommand.isAcceptableId(commandId)) {
            return Optional.empty();
        }
        return transactions.inTransaction(() -> store.find(commandId)).map(WorkerWrites::statusOf);
    }

    /**
     * 게이트를 통과한 명령을 limit개까지 잡는다. 보낼 수 있게 된 시각이 이른 것부터, 같으면 접수 순서로 잡는다
     * ({@link io.github.ledgerrelay.core.spi.CommandStore#lockDue}). 트랜잭션 밖에서 부른다.
     */
    public ClaimBatch claim(int limit, Duration lease) {
        WorkerWrites.requireOutsideTransaction(transactions);
        return transactions.inTransaction(() -> {
            // 앞 명령이 끝난 키의 맨 앞을 넘긴다. 앱이 그 키 행을 쥐고 있으면 기다리지 않고 다음 claim으로 미룬다
            keys.passOnFinishedHeads(limit);
            List<ClaimedCommand> claimed = new ArrayList<>();
            List<String> halted = new ArrayList<>();
            List<ClaimBatch.Problem> problems = new ArrayList<>();
            for (DueCommand due : store.lockDue(limit)) {
                CommandRecord candidate = due.command();
                // lockDue가 처리 중 명령을 내는 것은 lease가 끝났을 때뿐이다
                boolean leaseExpired = candidate.row().state() == StoredState.CLAIMED;
                GateDecision gate;
                StepResult result;
                HaltReason haltReason = HaltReason.MALFORMED_ROW;
                try {
                    gate = OrderingGate.decide(candidate.kind(), candidate.reservationCommandId(), candidate.seq(),
                            due.reservation().stream().toList());
                    result = CommandStateMachine.apply(candidate.row(), new Step.Claim(gate, leaseExpired));
                    if (gate == GateDecision.HALT) {
                        haltReason = OrderingGate.explainHalt(candidate.kind(), candidate.reservationCommandId(),
                                candidate.seq(), due.reservation().stream().toList());
                    }
                } catch (RuntimeException malformed) {
                    // 행의 데이터가 규칙과 어긋났다(DB 직접 수정, 저장소 버그). 판단은 순수 Java라 트랜잭션은 멀쩡하다.
                    // 그 행만 멈춰 그 키만 막고, 다른 후보는 계속 판단한다(F29). 멈추게도 못 하면 건너뛴다. 어느 쪽이든
                    // 이유를 실어 알린다
                    gate = GateDecision.HALT;
                    try {
                        result = CommandStateMachine.apply(candidate.row(), new Step.Claim(gate, leaseExpired));
                    } catch (RuntimeException unparkable) {
                        malformed.addSuppressed(unparkable);
                        problems.add(new ClaimBatch.Problem(candidate.commandId(), malformed, false));
                        continue;
                    }
                    problems.add(new ClaimBatch.Problem(candidate.commandId(), malformed, true));
                }
                if (!(result instanceof StepResult.Moved moved)) {
                    continue;
                }
                // 멈출 명령은 HALTED로 주차해 그 키만 막는다. 다시 후보가 되지 않으므로 알림은 이때 한 번이다(F9)
                boolean halting = moved.row().state() == StoredState.HALTED;
                RowChange.Stamp stamp = halting ? new RowChange.Stamp.Halt(haltReason) : new RowChange.Stamp.LeaseFor(lease);
                if (!store.compareAndSet(new RowChange(candidate.rowId(), candidate.row().state(),
                        candidate.row().leaseToken(), moved.row(), stamp))) {
                    continue;
                }
                if (halting) {
                    halted.add(candidate.commandId());
                } else {
                    claimed.add(claimedOf(candidate, moved.row(), gate == GateDecision.SKIP));
                }
            }
            return new ClaimBatch(claimed, halted, problems);
        });
    }

    private static ClaimedCommand claimedOf(CommandRecord command, CommandRow claimedRow, boolean skip) {
        Submission submission = new Submission(command.transferId(), BigInteger.valueOf(command.rowId()),
                command.body());
        return new ClaimedCommand(command.rowId(), command.commandId(), command.orderingKey(),
                claimedRow.leaseToken(), skip, claimedRow.knownResult(), submission);
    }

    /** TB에 보내기 직전에 제출을 기록한다. 트랜잭션 밖에서 부른다. */
    public WriteResult markSubmitted(ClaimedCommand claimed) {
        return workerWrites.markSubmitted(claimed);
    }

    /**
     * TB 결과로 끝내고, 같은 트랜잭션에서 핸들러를 실행한다(G6). 트랜잭션 밖에서 부른다.
     * 핸들러가 Error를 던지면 받은 결과를 새 트랜잭션에 남긴 뒤 그 Error를 그대로 던진다.
     */
    public WriteResult complete(ClaimedCommand claimed, LedgerResultCode code) {
        return workerWrites.complete(claimed, code);
    }

    /** 참조한 예약이 적용되지 않아 건너뛰고, 같은 트랜잭션에서 핸들러를 실행한다. 트랜잭션 밖에서 부른다. */
    public WriteResult skip(ClaimedCommand claimed) {
        return workerWrites.skip(claimed);
    }

    /** 응답이 없거나 예외가 나서 되돌린다. backoff가 지나야 다시 잡힌다. 트랜잭션 밖에서 부른다. */
    public WriteResult release(ClaimedCommand claimed) {
        return workerWrites.release(claimed);
    }

    /**
     * 운영자가 멈춘(HALTED) 명령을 포기한다(docs/failure-modes.md F9). TB에 보내지 않고 건너뜀으로 끝내며,
     * 같은 트랜잭션에서 핸들러가 '운영자 포기' 사유를 받는다. 핸들러가 실패하면 멈춘 그대로 남는다. 트랜잭션 밖에서 부른다.
     *
     * @throws io.github.ledgerrelay.core.CommandNotFoundException 접수한 적 없는 명령
     * @throws io.github.ledgerrelay.core.CannotAbandonException   멈춘 명령이 아니거나, 이미 TB에 보낸 적이 있다(F27)
     */
    public WriteResult abandonHalted(String commandId) {
        return workerWrites.abandonHalted(commandId);
    }

    private BigInteger reservationTransferId(LedgerCommand command, String reservationId) {
        return store.find(reservationId)
                .filter(reservation -> !reservationId.equals(command.commandId()))
                .filter(reservation -> reservation.orderingKey().equals(command.orderingKey()))
                .filter(reservation -> reservation.kind() == CommandKind.RESERVE)
                .filter(reservation -> !reservation.acceptedInThisTransaction())
                .map(CommandRecord::transferId)
                .orElseThrow(() -> new ReservationNotFoundException(command.commandId(), reservationId,
                        command.orderingKey()));
    }

    private static CommandReceipt sameOrConflict(CommandRecord existing, String hash) {
        if (!existing.contentHash().equals(hash)) {
            throw new CommandConflictException(existing.commandId());
        }
        return new CommandReceipt(existing.commandId(), existing.transferId());
    }
}
