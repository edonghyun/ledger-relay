package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.Rejection;
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

/** 명령의 일생을 맡는 서비스. 업무 판단은 모두 여기와 domain에서 한다. 저장소는 저장만 한다. */
public final class CommandLifecycle implements LedgerRelay {

    private final CommandStore store;
    private final Transactions transactions;
    private final TransferIdGenerator transferIds;
    private final LedgerOutcomeHandler handler;

    public CommandLifecycle(CommandStore store, Transactions transactions, TransferIdGenerator transferIds,
            LedgerOutcomeHandler handler) {
        this.store = store;
        this.transactions = transactions;
        this.transferIds = transferIds;
        this.handler = handler;
    }

    @Override
    public CommandReceipt submit(LedgerCommand command) {
        transactions.requireActive(); // G1: 앱 트랜잭션에 묶인다
        // §5.1-2: 키를 잠그고 순번을 받는다. 같은 키의 동시 접수는 여기서 줄을 서므로 순번 순서가 커밋 순서와 같다
        long seq = store.nextSeq(command.orderingKey());
        // §5.1-3: 참조 검사. 예약의 이체 ID는 영구히 바뀌지 않으므로 지금 정해 둔다(P2a-20)
        Optional<BigInteger> pendingTransferId = command.reservation()
                .map(reservationId -> reservationTransferId(command, reservationId));
        // §5.1-4: 같은 commandId가 있으면 내용을 견준다
        String hash = ContentHash.of(command);
        Optional<CommandRecord> existing = store.find(command.commandId());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), hash);
        }
        // §5.1-5: 이체 ID를 내고 넣는다. 그사이 다른 트랜잭션이 같은 commandId를 넣었다면 저장소가 그 행을 돌려준다
        BigInteger transferId = transferIds.next();
        NewCommand row = new NewCommand(command.commandId(), command.orderingKey(), seq, command.reservation(),
                transferId, hash, TransferBody.of(command, pendingTransferId));
        return store.insertIfAbsent(row)
                .map(raced -> sameOrConflict(raced, hash))
                .orElseGet(() -> new CommandReceipt(command.commandId(), transferId));
    }

    @Override
    public Optional<CommandStatus> status(String commandId) {
        return transactions.inTransaction(() -> store.find(commandId)).map(CommandLifecycle::statusOf);
    }

    /** 게이트(§5.2)를 통과한 명령을 접수 순서로 limit개까지 잡는다. 트랜잭션 밖에서 부른다. */
    public ClaimBatch claim(int limit, Duration lease) {
        return transactions.inTransaction(() -> {
            List<ClaimedCommand> claimed = new ArrayList<>();
            List<String> halted = new ArrayList<>();
            for (DueCommand due : store.lockDue(limit)) {
                CommandRecord candidate = due.command();
                GateDecision gate = OrderingGate.decide(candidate.kind(), candidate.reservationCommandId(),
                        candidate.seq(), due.reservation().stream().toList());
                // lockDue가 처리 중 명령을 내는 것은 lease가 끝났을 때뿐이다
                boolean leaseExpired = candidate.row().state() == StoredState.CLAIMED;
                StepResult result = CommandStateMachine.apply(candidate.row(), new Step.Claim(gate, leaseExpired));
                if (result instanceof StepResult.Moved moved && store.compareAndSet(new RowChange(candidate.rowId(),
                        candidate.row().state(), candidate.row().leaseToken(), moved.row(),
                        new RowChange.Stamp.LeaseFor(lease)))) {
                    claimed.add(claimedOf(candidate, moved.row(), gate == GateDecision.SKIP));
                } else if (gate == GateDecision.HALT) {
                    halted.add(candidate.commandId());
                }
            }
            return new ClaimBatch(claimed, halted);
        });
    }

    private static ClaimedCommand claimedOf(CommandRecord command, CommandRow claimedRow, boolean skip) {
        Submission submission = new Submission(command.transferId(), BigInteger.valueOf(command.rowId()),
                command.body());
        return new ClaimedCommand(command.rowId(), command.commandId(), command.orderingKey(),
                claimedRow.leaseToken(), skip, claimedRow.knownResult(), submission);
    }

    private BigInteger reservationTransferId(LedgerCommand command, String reservationId) {
        return store.find(reservationId)
                .filter(reservation -> !reservationId.equals(command.commandId()))
                .filter(reservation -> reservation.orderingKey().equals(command.orderingKey()))
                .filter(reservation -> reservation.kind() == CommandKind.RESERVE)
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

    /** 저장된 행을 앱이 보는 조회 결과로 바꾼다(§4.2, §6). */
    static CommandStatus statusOf(CommandRecord record) {
        CommandRow row = record.row();
        Optional<Rejection> rejection = row.state() == StoredState.REJECTED || row.state() == StoredState.CONFLICT
                ? row.resultCode().map(Rejection::of)
                : Optional.empty();
        return new CommandStatus(record.commandId(), row.state().queryState(row.submitCount()), rejection,
                record.transferId(), row.submitCount(), record.acceptedAt(), record.completedAt());
    }
}
