package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.ResultCategory;

/**
 * The stored state. The query state the app sees is the public CommandStatus.State. "Unresolved" is not a state; it
 * is derived from submit_count.
 */
public enum StoredState {
    PENDING,
    CLAIMED,
    APPLIED,
    REJECTED,
    CONFLICT,
    SKIPPED,
    /**
     * Halted because the input cannot occur on the normal path. It is not final, so the key stays blocked. Only an
     * operator can resolve it.
     */
    HALTED;

    /** A final state never changes again (G5). */
    public boolean isFinal() {
        return this == APPLIED || this == REJECTED || this == CONFLICT || this == SKIPPED;
    }

    /** The state an in-progress command ends in after receiving a TB result category. */
    public static StoredState finalStateFor(ResultCategory category) {
        return switch (category) {
            case APPLIED -> APPLIED;
            case CONFLICT, UNSUPPORTED -> CONFLICT;
            case BUSINESS, PRIOR_FAILURE, INVALID -> REJECTED;
        };
    }

    /**
     * The query state. Accepted and in progress differ by whether it was ever sent to TB. An unresolved send with a
     * lost response is in progress too.
     */
    public CommandStatus.State queryState(long submitCount) {
        if (submitCount < 0) {
            throw new IllegalArgumentException("submitCount must be at least 0: " + submitCount);
        }
        return switch (this) {
            case PENDING, CLAIMED -> submitCount == 0 ? CommandStatus.State.ACCEPTED : CommandStatus.State.IN_PROGRESS;
            case APPLIED -> CommandStatus.State.APPLIED;
            case REJECTED -> CommandStatus.State.REJECTED;
            case CONFLICT -> CommandStatus.State.CONFLICT;
            case SKIPPED -> CommandStatus.State.SKIPPED;
            case HALTED -> CommandStatus.State.HALTED;
        };
    }
}
