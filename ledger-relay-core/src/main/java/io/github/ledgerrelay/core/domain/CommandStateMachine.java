package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.SkipReason;
import java.util.Optional;

/**
 * The state machine, tokens included. The application services run it, and it is also the oracle for the randomized
 * checks (E6a).
 *
 * <p>A claim receives a token. Every other write presents the token received at claim time and takes effect only if
 * the row is in progress (CLAIMED) and the token matches. Otherwise it is zero rows (G5). The CLAIMED row of the
 * transition table decides the target and effects of a write that takes effect. Reaching a cell the table marks
 * "impossible" or "not applicable" is a bug in the module, so it throws {@link IllegalStateException}.
 */
public final class CommandStateMachine {

    private CommandStateMachine() {
    }

    public static StepResult apply(CommandRow row, Step step) {
        return switch (step) {
            case Step.Claim claim -> claim(row, claim);
            case Step.Write write -> write(row, write);
            case Step.Abandon abandon -> follow(row, CommandEvent.ABANDONED_BY_OPERATOR, abandon);
        };
    }

    private static StepResult claim(CommandRow row, Step.Claim claim) {
        CommandEvent event = switch (row.state()) {
            case PENDING -> claim.gate().claimEvent();
            // An in-flight command is claimed again only when its lease has expired. While it is live, this reaches the
            // "impossible" cell of the table. The gate decision is ignored: an in-flight command already passed the
            // gate
            // when claimed, and the gate inputs (earlier commands of the same key, the referenced reservation) only
            // move
            // forward after that. Making this row a halt target takes a direct DB edit.
            case CLAIMED -> claim.leaseExpired() ? CommandEvent.LEASE_EXPIRED : CommandEvent.CLAIM_WITH_GATE_OPEN;
            case APPLIED, REJECTED, CONFLICT, SKIPPED, HALTED -> CommandEvent.CLAIM_WITH_GATE_OPEN;
        };
        return follow(row, event, claim);
    }

    private static StepResult write(CommandRow row, Step.Write write) {
        // jdbc: UPDATE … WHERE id = ? AND lease_token = ? AND state = 'CLAIMED'
        if (row.state() != StoredState.CLAIMED || row.leaseToken() != write.token()) {
            return new StepResult.Fenced();
        }
        CommandEvent event = switch (write) {
            case Step.MarkSubmitted submitted -> {
                if (row.knownResult().isPresent()) {
                    throw bug("attempt to resend to TB a command with a known result (" + row.knownResult().get() + "). "
                            + "Resending masks the original reason with IdAlreadyFailed");
                }
                yield CommandEvent.SUBMITTING;
            }
            case Step.Complete complete -> {
                if (row.knownResult().isPresent() && row.knownResult().get() != complete.code()) {
                    throw bug("the known result is " + row.knownResult().get() + ", but the attempt ends the command with " + complete.code());
                }
                yield CommandEvent.of(complete.code().category());
            }
            case Step.Skip skip -> CommandEvent.RESERVATION_NOT_APPLIED;
            case Step.Release release -> CommandEvent.NO_REPLY_OR_ERROR;
            case Step.HandlerFailed failed -> CommandEvent.HANDLER_FAILED;
        };
        return follow(row, event, write);
    }

    private static StepResult follow(CommandRow row, CommandEvent event, Step step) {
        Transition transition = TransitionTable.next(row.state(), event);
        return switch (transition.outcome()) {
            case MOVE -> new StepResult.Moved(moved(row, transition, step), transition.effects());
            case UNCHANGED -> new StepResult.Unchanged();
            case ZERO_ROWS -> new StepResult.Fenced();
            case IMPOSSIBLE, NOT_APPLICABLE ->
                    throw bug("event " + event + " cannot occur in state " + row.state() + " (transition table: " + transition.outcome() + ")");
        };
    }

    private static CommandRow moved(CommandRow row, Transition transition, Step step) {
        long token = row.leaseToken();
        long submitCount = row.submitCount();
        Optional<LedgerResultCode> knownResult = row.knownResult();
        int handlerFailures = row.handlerFailures();
        for (Transition.Effect effect : transition.effects()) {
            switch (effect) {
                case INCREMENT_LEASE_TOKEN -> token++;
                case RECORD_SUBMISSION -> submitCount++;
                case KEEP_KNOWN_RESULT -> knownResult = ((Step.HandlerFailed) step).knownResult();
                case COUNT_HANDLER_FAILURE -> handlerFailures++;
                // The service handles these: running the handler in the same transaction, and the next attempt time by
                // the store clock (PG now())
                case RUN_HANDLER, BACKOFF -> { }
            }
        }
        Optional<LedgerResultCode> resultCode = step instanceof Step.Complete complete
                ? Optional.of(complete.code())
                : row.resultCode();
        Optional<SkipReason> skipReason = switch (step) {
            case Step.Skip skip -> Optional.of(SkipReason.RESERVATION_NOT_APPLIED);
            case Step.Abandon abandon -> Optional.of(SkipReason.ABANDONED_BY_OPERATOR);
            default -> row.skipReason();
        };
        return new CommandRow(transition.to(), token, submitCount, knownResult, resultCode, handlerFailures, skipReason);
    }

    private static IllegalStateException bug(String what) {
        return new IllegalStateException("Bug: " + what);
    }
}
