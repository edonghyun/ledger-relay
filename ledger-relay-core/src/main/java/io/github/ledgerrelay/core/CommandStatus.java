package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The query result for one command, and the value the outcome handler receives.
 * {@link #state()} is the query state. {@link #rejection()} is present only when the command ended as rejected or
 * conflict.
 */
public record CommandStatus(
        String commandId,
        State state,
        Optional<Rejection> rejection,
        Optional<SkipReason> skipReason,
        BigInteger transferId,
        long submitCount,
        Instant acceptedAt,
        Optional<Instant> completedAt) {

    /** The query state. */
    public enum State {
        /** Accepted and not yet sent to TB. */
        ACCEPTED,
        /** Sent to TB and waiting for the result. An unresolved send whose response was lost also counts here. */
        IN_PROGRESS,
        APPLIED,
        REJECTED,
        CONFLICT,
        SKIPPED,
        /**
         * Halted because the input cannot occur on the normal path. The key stays blocked until an operator resolves
         * it.
         */
        HALTED;

        public boolean isFinal() {
            return this != ACCEPTED && this != IN_PROGRESS && this != HALTED;
        }
    }

    public CommandStatus {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rejection, "rejection");
        Objects.requireNonNull(skipReason, "skipReason");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        boolean needsReason = state == State.REJECTED || state == State.CONFLICT;
        if (rejection.isPresent() != needsReason) {
            throw new IllegalArgumentException(
                    "rejection must be present exactly when state is REJECTED or CONFLICT: state=" + state + ", rejection=" + rejection);
        }
        rejection.ifPresent(reason -> {
            // The final state the category leads to. The public API does not know domain, so it decides again here;
            // StoredStateTest checks that this matches StoredState.finalStateFor in domain.
            State expected = switch (reason.category()) {
                case CONFLICT, UNSUPPORTED -> State.CONFLICT;
                case BUSINESS, PRIOR_FAILURE, INVALID -> State.REJECTED;
                case APPLIED -> State.APPLIED;
            };
            if (expected != state) {
                throw new IllegalArgumentException(reason.tbCode() + " (" + reason.category() + ") is not a reason for state " + state);
            }
        });
        if (skipReason.isPresent() != (state == State.SKIPPED)) {
            throw new IllegalArgumentException(
                    "skipReason must be present exactly when state is SKIPPED: state=" + state + ", skipReason=" + skipReason);
        }
        if (completedAt.isPresent() != state.isFinal()) {
            throw new IllegalArgumentException("completedAt must be present exactly when the command has ended: state=" + state);
        }
    }
}
