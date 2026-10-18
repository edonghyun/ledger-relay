package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.CommandBacklog;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Reads the backlog on its own thread at a fixed delay and keeps the last sample. Gauges read the sample, so a scrape
 * never queries the DB, and a slow or unreachable DB never makes a scrape, health or a worker wait. A failed read
 * keeps the previous sample and is counted.
 */
public final class BacklogSampler implements AutoCloseable {

    private final Supplier<CommandBacklog> source;
    private final Duration interval;
    private final AtomicReference<CommandBacklog> latest = new AtomicReference<>();
    private final AtomicLong failures = new AtomicLong();
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread sampler = new Thread(runnable, "ledger-relay-backlog-sampler");
        sampler.setDaemon(true);
        return sampler;
    });

    public BacklogSampler(Supplier<CommandBacklog> source, Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("ledger-relay.metrics.backlog-interval must be positive: " + interval);
        }
        this.source = source;
        this.interval = interval;
    }

    public void start() {
        thread.scheduleWithFixedDelay(this::sample, 0, interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void sample() {
        try {
            latest.set(source.get());
        } catch (RuntimeException e) {
            failures.incrementAndGet();
        }
    }

    /** The last successful sample. Empty until the first read succeeds. */
    public Optional<CommandBacklog> latest() {
        return Optional.ofNullable(latest.get());
    }

    /** Reads that failed since start. */
    public long failures() {
        return failures.get();
    }

    @Override
    public void close() {
        thread.shutdownNow();
    }
}
