package io.github.ledgerrelay.core.application;

import java.util.Objects;

/** The result of one worker write. The worker records metrics and logs from this alone. */
public sealed interface WriteResult {

    /** The write took effect. On a final state, the handler also ran to completion. */
    record Written() implements WriteResult {
    }

    /**
     * Zero rows (G5). Another worker took the command or it already ended. A normal race outcome
     * (stale_write_rejected).
     */
    record Fenced() implements WriteResult {
    }

    /**
     * The outcome handler threw. The final write was undone; the received result is kept and the command is claimed
     * again after the backoff.
     */
    record HandlerFailed(RuntimeException cause) implements WriteResult {
        public HandlerFailed {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
