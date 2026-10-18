package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.HaltReason;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import java.time.Duration;
import java.util.Objects;

/**
 * One conditional write (G5). Changes the row to {@code next} only if it is in {@code expectedState} and its token is
 * {@code expectedToken}. jdbc: {@code UPDATE … WHERE id = ? AND state = ? AND lease_token = ?}.
 *
 * @param next  the next row the state machine computed. The store writes it as is
 * @param stamp the timestamp column to update along with it. The time comes from the store's clock (PG now())
 */
public record RowChange(long rowId, StoredState expectedState, long expectedToken, CommandRow next, Stamp stamp) {

    public RowChange {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(stamp, "stamp");
    }

    /** The timestamp set together with the write. */
    public sealed interface Stamp {

        /** claim: lease_expires_at = now + lease. */
        record LeaseFor(Duration lease) implements Stamp {
        }

        /** Recording the submission: first_submitted_at = now if it is empty. */
        record FirstSubmission() implements Stamp {
        }

        /** Release or handler failure: next_attempt_at = now + backoff. */
        record RetryAfter(Duration backoff) implements Stamp {
        }

        /** Halt: records the reason and halted_at = now. */
        record Halt(HaltReason reason) implements Stamp {
            public Halt {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** Final state: completed_at = now. Clears the halt record. */
        record Completion() implements Stamp {
        }
    }
}
