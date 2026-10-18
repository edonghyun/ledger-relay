package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandStatus;
import java.util.Objects;
import java.util.Optional;

/** The result of one worker write. The worker records metrics and logs from this alone. */
public sealed interface WriteResult {

    /**
     * The write took effect. On a final state, the handler also ran to completion and {@code finished} holds the
     * outcome the handler received.
     */
    record Written(Optional<CommandStatus> finished) implements WriteResult {

        /** A write that did not reach a final state. */
        public static final Written NOT_FINAL = new Written(Optional.empty());

        public Written {
            Objects.requireNonNull(finished, "finished");
        }
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
