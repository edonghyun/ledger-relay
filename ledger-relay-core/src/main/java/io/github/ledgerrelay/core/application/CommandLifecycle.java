package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.LedgerResultCode;
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
import io.github.ledgerrelay.core.domain.Transition;
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
import java.util.Set;

/** 명령의 일생을 맡는 서비스. 업무 판단은 모두 여기와 domain에서 한다. 저장소는 저장만 한다. */
public final class CommandLifecycle implements LedgerRelay {

    private final CommandStore store;
    private final Transactions transactions;
    private final TransferIdGenerator transferIds;
    private final LedgerOutcomeHandler handler;
    private final Backoff backoff;

    public CommandLifecycle(CommandStore store, Transactions transactions, TransferIdGenerator transferIds,
            LedgerOutcomeHandler handler, Backoff backoff) {
        this.store = store;
        this.transactions = transactions;
        this.transferIds = transferIds;
        this.handler = handler;
        this.backoff = backoff;
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
        if (!LedgerCommand.isAcceptableId(commandId)) {
            return Optional.empty();
        }
        return transactions.inTransaction(() -> store.find(commandId)).map(CommandLifecycle::statusOf);
    }

    /** 게이트(§5.2)를 통과한 명령을 접수 순서로 limit개까지 잡는다. 트랜잭션 밖에서 부른다. */
    public ClaimBatch claim(int limit, Duration lease) {
        requireOutsideTransaction();
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
                if (!(result instanceof StepResult.Moved moved)) {
                    continue;
                }
                // 멈출 명령은 HALTED로 주차해 그 키만 막는다. 다시 후보가 되지 않으므로 알림은 이때 한 번이다(F9)
                boolean halting = moved.row().state() == StoredState.HALTED;
                RowChange.Stamp stamp = halting ? new RowChange.Stamp.Halt() : new RowChange.Stamp.LeaseFor(lease);
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
            return new ClaimBatch(claimed, halted);
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
        requireOutsideTransaction();
        return transactions.inTransaction(() -> write(claimed, new Step.MarkSubmitted(claimed.leaseToken())));
    }

    /**
     * TB 결과로 끝내고, 같은 트랜잭션에서 핸들러를 실행한다(G6). 트랜잭션 밖에서 부른다.
     * 핸들러가 Error를 던지면 받은 결과를 새 트랜잭션에 남긴 뒤 그 Error를 그대로 던진다.
     */
    public WriteResult complete(ClaimedCommand claimed, LedgerResultCode code) {
        requireOutsideTransaction();
        return finish(claimed, new Step.Complete(claimed.leaseToken(), code), Optional.of(code));
    }

    /** 참조한 예약이 적용되지 않아 건너뛰고, 같은 트랜잭션에서 핸들러를 실행한다. 트랜잭션 밖에서 부른다. */
    public WriteResult skip(ClaimedCommand claimed) {
        requireOutsideTransaction();
        return finish(claimed, new Step.Skip(claimed.leaseToken()), Optional.empty());
    }

    /** 응답이 없거나 예외가 나서 되돌린다. backoff가 지나야 다시 잡힌다. 트랜잭션 밖에서 부른다. */
    public WriteResult release(ClaimedCommand claimed) {
        requireOutsideTransaction();
        return transactions.inTransaction(() -> write(claimed, new Step.Release(claimed.leaseToken())));
    }

    /**
     * 운영자가 멈춘(HALTED) 명령을 포기한다(§5.2 HALT, docs/failure-modes.md F9). TB에 보내지 않고 건너뜀으로 끝내며,
     * 같은 트랜잭션에서 핸들러가 '운영자 포기' 사유를 받는다. 핸들러가 실패하면 멈춘 그대로 남는다. 트랜잭션 밖에서 부른다.
     *
     * @throws IllegalArgumentException 접수한 적 없는 명령
     * @throws IllegalStateException    멈춘 명령이 아니다
     */
    public WriteResult abandonHalted(String commandId) {
        requireOutsideTransaction();
        if (!LedgerCommand.isAcceptableId(commandId)) {
            throw new IllegalArgumentException("접수한 적 없는 명령입니다: " + commandId);
        }
        try {
            return transactions.inTransaction(() -> {
                CommandRecord current = store.find(commandId)
                        .orElseThrow(() -> new IllegalArgumentException("접수한 적 없는 명령입니다: " + commandId));
                if (current.row().state() != StoredState.HALTED) {
                    throw new IllegalStateException("멈춘(HALTED) 명령만 포기할 수 있습니다: " + commandId + "는 "
                            + current.row().state());
                }
                StepResult.Moved moved = (StepResult.Moved) CommandStateMachine.apply(current.row(), new Step.Abandon());
                return land(current, StoredState.HALTED, current.row().leaseToken(), moved);
            });
        } catch (HandlerFailedSignal failed) {
            // 롤백되어 멈춘 그대로다. 받은 TB 결과가 없으므로 따로 기록할 것도 없다
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            return new WriteResult.HandlerFailed((RuntimeException) failed.getCause());
        }
    }

    /**
     * 최종 쓰기. 핸들러가 던지면 그 트랜잭션은 롤백되어 결과 쓰기도 없던 일이 된다(G6). 그다음 새 트랜잭션에서
     * 받은 결과를 남기고 backoff 뒤 다시 잡히게 한다(§5.4, P2a-22).
     */
    private WriteResult finish(ClaimedCommand claimed, Step.Write step, Optional<LedgerResultCode> result) {
        try {
            return transactions.inTransaction(() -> write(claimed, step));
        } catch (HandlerFailedSignal failed) {
            transactions.inTransaction(() -> write(claimed, new Step.HandlerFailed(claimed.leaseToken(), result)));
            // Error(메모리 부족 등)는 삼키지 않는다. 그래도 받은 결과는 위에서 남겼으므로, 다시 TB에 보내
            // 원래 사유를 IdAlreadyFailed로 잃지 않는다(§5.4)
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            return new WriteResult.HandlerFailed((RuntimeException) failed.getCause());
        }
    }

    /** 읽고, 상태 기계로 다음 행을 계산하고, 상태와 토큰이 맞을 때만 쓴다. 최종 상태면 핸들러를 실행한다. */
    private WriteResult write(ClaimedCommand claimed, Step.Write step) {
        Optional<CommandRecord> found = store.find(claimed.commandId())
                .filter(current -> current.rowId() == claimed.rowId());
        if (found.isEmpty()) {
            return new WriteResult.Fenced();
        }
        CommandRecord current = found.get();
        if (!(CommandStateMachine.apply(current.row(), step) instanceof StepResult.Moved moved)) {
            return new WriteResult.Fenced();
        }
        return land(current, StoredState.CLAIMED, step.token(), moved);
    }

    /**
     * 상태 기계가 계산한 행을 조건부로 쓰고, 최종 상태면 같은 트랜잭션에서 핸들러를 실행한다. 상태 기계가 이미
     * 막았지만, 읽은 뒤 다른 워커가 바꿨을 수 있으므로 저장소에서 한 번 더 막는다(P2a-7).
     */
    private WriteResult land(CommandRecord current, StoredState expectedState, long expectedToken,
            StepResult.Moved moved) {
        boolean landed = store.compareAndSet(new RowChange(current.rowId(), expectedState, expectedToken,
                moved.row(), stampFor(moved)));
        if (!landed) {
            return new WriteResult.Fenced();
        }
        if (moved.effects().contains(Transition.Effect.RUN_HANDLER)) {
            // 방금 쓴 행을 다시 읽어 완료 시각까지 담는다
            CommandStatus outcome = statusOf(store.find(current.commandId()).orElseThrow());
            try {
                handler.onCompleted(outcome);
            } catch (RuntimeException | Error e) {
                throw new HandlerFailedSignal(e);
            }
        }
        return new WriteResult.Written();
    }

    /**
     * 워커 단계는 단계마다 자기 트랜잭션을 연다. 바깥 트랜잭션에 합류하면 핸들러 실패의 롤백이 바깥에 묻혀,
     * 결과만 커밋되고 핸들러 효과는 없는 상태가 될 수 있다(G6).
     */
    private void requireOutsideTransaction() {
        if (transactions.isActive()) {
            throw new IllegalStateException("버그: 워커 단계(claim, markSubmitted, complete, skip, release)와 abandonHalted는 "
                    + "트랜잭션 밖에서 부른다. 바깥 트랜잭션에 합류하면 핸들러 실패가 롤백되지 않는다(G6)");
        }
    }

    /** 전이가 함께 바꿀 시각 컬럼(§5.2~§5.4). */
    private RowChange.Stamp stampFor(StepResult.Moved moved) {
        CommandRow next = moved.row();
        Set<Transition.Effect> effects = moved.effects();
        if (next.state().isFinal()) {
            return new RowChange.Stamp.Completion();
        }
        if (effects.contains(Transition.Effect.COUNT_HANDLER_FAILURE)) {
            return new RowChange.Stamp.RetryAfter(backoff.after(next.handlerFailures()));
        }
        if (effects.contains(Transition.Effect.BACKOFF)) {
            return new RowChange.Stamp.RetryAfter(backoff.after(next.submitCount()));
        }
        if (effects.contains(Transition.Effect.RECORD_SUBMISSION)) {
            return new RowChange.Stamp.FirstSubmission();
        }
        throw new IllegalStateException("버그: 시각 컬럼을 정할 수 없는 전이다: " + moved);
    }

    /** 핸들러가 던졌다는 신호. 트랜잭션 밖으로 나가 롤백을 일으킨 뒤 {@link #finish}가 받는다. */
    private static final class HandlerFailedSignal extends RuntimeException {

        HandlerFailedSignal(Throwable handlerError) {
            super(handlerError);
        }
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

    /** 저장된 행을 앱이 보는 조회 결과로 바꾼다(§4.2, §6). */
    static CommandStatus statusOf(CommandRecord record) {
        CommandRow row = record.row();
        Optional<Rejection> rejection = row.state() == StoredState.REJECTED || row.state() == StoredState.CONFLICT
                ? row.resultCode().map(Rejection::of)
                : Optional.empty();
        return new CommandStatus(record.commandId(), row.state().queryState(row.submitCount()), rejection,
                row.skipReason(),
                record.transferId(), row.submitCount(), record.acceptedAt(), record.completedAt());
    }
}
