package io.github.ledgerrelay.core.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/** 손으로 돌리는 시계. 테스트는 sleep하지 않고 시계를 돌린다. */
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
