package io.github.ledgerrelay.core.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/** A clock turned by hand. Tests turn the clock instead of sleeping. */
public final class ManualClock implements Supplier<Instant> {

    private Instant now = Instant.parse("2026-10-16T00:00:00Z");

    @Override
    public synchronized Instant get() {
        return now;
    }

    public synchronized void advance(Duration duration) {
        now = now.plus(duration);
    }
}
