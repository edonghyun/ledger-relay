package io.github.ledgerrelay.core.application;

import java.time.Duration;
import java.util.Objects;

/** The wait before the next attempt (worker.backoff). It doubles on each failure and stops at max. */
public record Backoff(Duration initial, Duration max) {

    public Backoff {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("backoff initial must be longer than 0: " + initial);
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("backoff max (" + max + ") cannot be shorter than initial (" + initial + ")");
        }
    }

    /** @param failures the number of failures so far. 0 and 1 give initial */
    public Duration after(long failures) {
        Duration wait = initial;
        // The loop stops at the cap, so a large failures value does not overflow
        for (long n = 1; n < failures && wait.compareTo(max) < 0; n++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
