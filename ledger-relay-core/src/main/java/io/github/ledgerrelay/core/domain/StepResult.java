package io.github.ledgerrelay.core.domain;

import java.util.Set;

/** What the state machine did. */
public sealed interface StepResult {

    /** The row changed to {@link #row()}. The service handles BACKOFF and RUN_HANDLER among {@link #effects()}. */
    record Moved(CommandRow row, Set<Transition.Effect> effects) implements StepResult {
        public Moved {
            effects = Set.copyOf(effects);
        }
    }

    /** The gate was closed, so nothing happened. */
    record Unchanged() implements StepResult {
    }

    /**
     * The write hit zero rows because the token was stale or the row was not in progress. A normal race outcome
     * (stale_write_rejected).
     */
    record Fenced() implements StepResult {
    }
}
