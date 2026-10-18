package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.Rejection;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.CommandStateMachine;
import io.github.ledgerrelay.core.domain.Step;
import io.github.ledgerrelay.core.domain.StepResult;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.domain.Transition;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Transactions;
import java.util.Optional;
import java.util.Set;

/**
 * 잡은 명령에 대한 워커의 쓰기와, 최종 상태로 바꾼 같은 트랜잭션에서의 핸들러 실행(G6). 명령 행만 다루고 순서 키 행은
 * 잡지 않는다. 그래서 키 포트({@link io.github.ledgerrelay.core.spi.OrderingKeys})를 받지 않는다: 앱은 주문 행을 쥔 채
 * 접수하며 키 행을 기다릴 수 있으므로, 여기서 키 행을 쥔 채 핸들러가 주문 행을 기다리면 교착한다(02e P2e-7).
 * {@code ArchitectureTest}가 이 경계를 지킨다.
 */
final class WorkerWrites {

    private final CommandStore store;
    private final Transactions transactions;
    private final LedgerOutcomeHandler handler;
    private final Backoff backoff;

    WorkerWrites(CommandStore store, Transactions transactions, LedgerOutcomeHandler handler, Backoff backoff) {
        this.store = store;
        this.transactions = transactions;
        this.handler = handler;
        this.backoff = backoff;
    }

    /** TB에 보내기 직전에 제출을 기록한다. */
    WriteResult markSubmitted(ClaimedCommand claimed) {
        requireOutsideTransaction(transactions);
        return transactions.inTransaction(() -> write(claimed, new Step.MarkSubmitted(claimed.leaseToken())));
    }

    /** TB 결과로 끝내고, 같은 트랜잭션에서 핸들러를 실행한다. */
    WriteResult complete(ClaimedCommand claimed, LedgerResultCode code) {
        requireOutsideTransaction(transactions);
        return finish(claimed, new Step.Complete(claimed.leaseToken(), code), Optional.of(code));
    }

    /** 참조한 예약이 적용되지 않아 건너뛰고, 같은 트랜잭션에서 핸들러를 실행한다. */
    WriteResult skip(ClaimedCommand claimed) {
        requireOutsideTransaction(transactions);
        return finish(claimed, new Step.Skip(claimed.leaseToken()), Optional.empty());
    }

    /** 응답이 없거나 예외가 나서 되돌린다. */
    WriteResult release(ClaimedCommand claimed) {
        requireOutsideTransaction(transactions);
        return transactions.inTransaction(() -> write(claimed, new Step.Release(claimed.leaseToken())));
    }

    /** 운영자가 멈춘(HALTED) 명령을 포기한다. 규칙은 {@link CommandLifecycle#abandonHalted}에 적었다. */
    WriteResult abandonHalted(String commandId) {
        requireOutsideTransaction(transactions);
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
                // 포기는 "TB에 한 번도 가지 않았다"는 뜻이다(SkipReason.ABANDONED_BY_OPERATOR). 보낸 적이 있으면 TB에서
                // 이미 확정됐을 수 있어, 포기하면 돈은 움직였는데 PG와 핸들러는 적용 안 됨으로 남는다(F27)
                if (current.row().submitCount() > 0 || current.row().knownResult().isPresent()) {
                    throw new IllegalStateException("이미 TB에 보낸 적이 있어 포기할 수 없습니다: " + commandId
                            + ". TB에서 이체를 확인한 뒤 수동으로 정리하세요");
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

    /**
     * 워커 단계는 단계마다 자기 트랜잭션을 연다. 바깥 트랜잭션에 합류하면 핸들러 실패의 롤백이 바깥에 묻혀,
     * 결과만 커밋되고 핸들러 효과는 없는 상태가 될 수 있다(G6).
     */
    static void requireOutsideTransaction(Transactions transactions) {
        if (transactions.isActive()) {
            throw new IllegalStateException("버그: 워커 단계(claim, markSubmitted, complete, skip, release)와 abandonHalted는 "
                    + "트랜잭션 밖에서 부른다. 바깥 트랜잭션에 합류하면 핸들러 실패가 롤백되지 않는다(G6)");
        }
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

    /** 핸들러가 던졌다는 신호. 트랜잭션 밖으로 나가 롤백을 일으킨 뒤 {@link #finish}가 받는다. */
    private static final class HandlerFailedSignal extends RuntimeException {

        HandlerFailedSignal(Throwable handlerError) {
            super(handlerError);
        }
    }
}
