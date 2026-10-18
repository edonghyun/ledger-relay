package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CannotAbandonException;
import io.github.ledgerrelay.core.CommandNotFoundException;
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
 * The worker's writes for claimed commands, and running the handler in the same transaction that moves the command to
 * its final state (G6). It touches only command rows and never locks ordering key rows. That is why it does not take
 * the key port ({@link io.github.ledgerrelay.core.spi.OrderingKeys}): the app accepts while holding its order rows and
 * may wait for a key row, so if this class held a key row while the handler waited for an order row, they would
 * deadlock. {@code ArchitectureTest} enforces this boundary.
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

    /** Records the submission right before sending to TB. */
    WriteResult markSubmitted(ClaimedCommand claimed) {
        requireOutsideTransaction(transactions);
        return transactions.inTransaction(() -> write(claimed, new Step.MarkSubmitted(claimed.leaseToken())));
    }

    /** Ends the command with the TB result and runs the handler in the same transaction. */
    WriteResult complete(ClaimedCommand claimed, LedgerResultCode code) {
        requireOutsideTransaction(transactions);
        return finish(claimed, new Step.Complete(claimed.leaseToken(), code), Optional.of(code));
    }

    /** Skips because the referenced reservation was not applied, and runs the handler in the same transaction. */
    WriteResult skip(ClaimedCommand claimed) {
        requireOutsideTransaction(transactions);
        return finish(claimed, new Step.Skip(claimed.leaseToken()), Optional.empty());
    }

    /** Puts the command back after no response or an exception. */
    WriteResult release(ClaimedCommand claimed) {
        requireOutsideTransaction(transactions);
        return transactions.inTransaction(() -> write(claimed, new Step.Release(claimed.leaseToken())));
    }

    /**
     * An operator abandons a halted (HALTED) command. The rules are described on {@link
     * CommandLifecycle#abandonHalted}.
     */
    WriteResult abandonHalted(String commandId) {
        requireOutsideTransaction(transactions);
        if (!LedgerCommand.isAcceptableId(commandId)) {
            throw new CommandNotFoundException(commandId);
        }
        try {
            return transactions.inTransaction(() -> {
                CommandRecord current = store.find(commandId)
                        .orElseThrow(() -> new CommandNotFoundException(commandId));
                if (current.row().state() != StoredState.HALTED) {
                    throw new CannotAbandonException(commandId, CannotAbandonException.Reason.NOT_HALTED,
                            "Only a halted (HALTED) command can be abandoned: " + commandId + " is " + current.row().state());
                }
                // Abandoning means "it never went to TB" (SkipReason.ABANDONED_BY_OPERATOR). If it was sent, TB may
                // already
                // have applied it, and abandoning would leave PG and the handler at "not applied" while money moved
                // (F27)
                if (current.row().submitCount() > 0 || current.row().knownResult().isPresent()) {
                    throw new CannotAbandonException(commandId, CannotAbandonException.Reason.ALREADY_SUBMITTED,
                            "Cannot abandon a command that was already sent to TB: " + commandId + ". Check the transfer in TB, then clean up by hand");
                }
                StepResult.Moved moved = (StepResult.Moved) CommandStateMachine.apply(current.row(), new Step.Abandon());
                return land(current, StoredState.HALTED, current.row().leaseToken(), moved);
            });
        } catch (HandlerFailedSignal failed) {
            // Rolled back, so the command stays halted. There is no received TB result, so there is nothing else to
            // record
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            return new WriteResult.HandlerFailed((RuntimeException) failed.getCause());
        }
    }

    /**
     * The final write. If the handler throws, that transaction rolls back and writing the result is undone too (G6).
     * Then a new transaction records the received result so the command is claimed again after the backoff.
     */
    private WriteResult finish(ClaimedCommand claimed, Step.Write step, Optional<LedgerResultCode> result) {
        try {
            return transactions.inTransaction(() -> write(claimed, step));
        } catch (HandlerFailedSignal failed) {
            try {
                transactions.inTransaction(() -> write(claimed, new Step.HandlerFailed(claimed.leaseToken(), result)));
            } catch (RuntimeException | Error recordFailed) {
                // If even the record fails, this exception goes to the worker. Attach the handler exception, the
                // original cause, so it is not lost
                recordFailed.addSuppressed(failed.getCause());
                throw recordFailed;
            }
            // Do not swallow an Error (out of memory and so on). The received result was still recorded above, so a
            // resend to
            // TB does not lose the original reason to IdAlreadyFailed
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            return new WriteResult.HandlerFailed((RuntimeException) failed.getCause());
        }
    }

    /**
     * Reads, computes the next row with the state machine, and writes only if state and token match. Runs the handler
     * on a final state.
     */
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
     * Conditionally writes the row the state machine computed, and on a final state runs the handler in the same
     * transaction. The state machine already guards this, but another worker may have changed the row after the read,
     * so the store guards it once more.
     */
    private WriteResult land(CommandRecord current, StoredState expectedState, long expectedToken,
            StepResult.Moved moved) {
        boolean landed = store.compareAndSet(new RowChange(current.rowId(), expectedState, expectedToken,
                moved.row(), stampFor(moved)));
        if (!landed) {
            return new WriteResult.Fenced();
        }
        if (moved.effects().contains(Transition.Effect.RUN_HANDLER)) {
            // Read the row just written again so the outcome includes the completion time
            CommandStatus outcome = statusOf(store.find(current.commandId()).orElseThrow());
            try {
                handler.onCompleted(outcome);
            } catch (RuntimeException | Error e) {
                throw new HandlerFailedSignal(e);
            }
            return new WriteResult.Written(Optional.of(outcome));
        }
        return WriteResult.Written.NOT_FINAL;
    }

    /** The timestamp column the transition updates along with it. */
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
        throw new IllegalStateException("Bug: no timestamp column can be chosen for this transition: " + moved);
    }

    /**
     * Each worker step opens its own transaction. If it joined an outer transaction, the rollback from a handler
     * failure
     * would be buried in the outer one, and the result could commit without the handler's effects (G6).
     */
    static void requireOutsideTransaction(Transactions transactions) {
        if (transactions.isActive()) {
            throw new IllegalStateException("Bug: call the worker steps (claim, markSubmitted, complete, skip, release) and abandonHalted "
                    + "outside a transaction. Joining an outer transaction means a handler failure is not rolled back (G6)");
        }
    }

    /** Turns a stored row into the query result the app sees. */
    static CommandStatus statusOf(CommandRecord record) {
        CommandRow row = record.row();
        Optional<Rejection> rejection = row.state() == StoredState.REJECTED || row.state() == StoredState.CONFLICT
                ? row.resultCode().map(Rejection::of)
                : Optional.empty();
        return new CommandStatus(record.commandId(), row.state().queryState(row.submitCount()), rejection,
                row.skipReason(),
                record.transferId(), row.submitCount(), record.acceptedAt(), record.completedAt());
    }

    /**
     * Signals that the handler threw. It leaves the transaction to cause the rollback, then {@link #finish} catches it.
     */
    private static final class HandlerFailedSignal extends RuntimeException {

        HandlerFailedSignal(Throwable handlerError) {
            super(handlerError);
        }
    }
}
