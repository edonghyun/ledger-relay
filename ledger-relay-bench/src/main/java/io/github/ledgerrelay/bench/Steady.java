package io.github.ledgerrelay.bench;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Latency under a steady load: the workers run while commands are accepted at a fixed rate. Acceptance is paced in
 * 10 ms ticks by a pool of {@link #ACCEPT_THREADS} threads, like concurrent app requests. Commands accepted during
 * the warm-up are left out of the latencies.
 */
final class Steady {

    static final int ACCEPT_THREADS = 16;
    static final Duration TICK = Duration.ofMillis(10);
    static final Duration LIMIT = Duration.ofMinutes(5);

    private static final String FINISH_PERCENTILES = """
            SELECT round(1000 * percentile_cont(0.5) WITHIN GROUP (ORDER BY took))::bigint AS p50,
                   round(1000 * percentile_cont(0.99) WITHIN GROUP (ORDER BY took))::bigint AS p99
            FROM (SELECT extract(epoch FROM completed_at - accepted_at) AS took FROM ledger_command
                  WHERE accepted_at >= ? AND completed_at IS NOT NULL) measured
            """;

    private Steady() {
    }

    record Accepted(long startNanos, long tookNanos) {
    }

    static RunResult run(BenchEnvironment environment, Condition condition, int repetition) throws Exception {
        BenchEnvironment.Run run = environment.reset();
        Recorder recorder = new Recorder();
        List<Accepted> accepted = new ArrayList<>();
        String note = "";
        try (WaitSampler waits = new WaitSampler(environment.admin());
                Workers workers = Workers.start(run, condition.workers(), condition.batchSize(), recorder)) {
            Instant dbStart = run.dbNow();
            long start = System.nanoTime();
            int issued = pace(run, condition, start, accepted);
            double acceptedPerSecond = issued / (condition.duration().toNanos() / 1e9);
            if (issued < 0.95 * condition.rate() * condition.duration().toNanos() / 1e9) {
                note = "rate not sustained (" + Math.round(acceptedPerSecond) + "/s accepted)";
            }
            if (!run.awaitFinished(LIMIT)) {
                note = (note.isEmpty() ? "" : note + "; ") + "not finished within " + LIMIT;
            }
            workers.close();
            long warmupNanos = condition.warmup().toNanos();
            List<Long> acceptTimes;
            synchronized (accepted) {
                acceptTimes = accepted.stream().filter(a -> a.startNanos() - start >= warmupNanos)
                        .map(Accepted::tookNanos).toList();
            }
            Long acceptP99 = Percentiles.of(acceptTimes, 99);
            Long[] finish = JdbcClient.create(environment.admin()).sql(FINISH_PERCENTILES)
                    .param(OffsetDateTime.ofInstant(dbStart.plus(condition.warmup()), ZoneOffset.UTC))
                    .query((row, n) -> new Long[] {row.getObject("p50", Long.class), row.getObject("p99", Long.class)})
                    .single();
            return new RunResult(condition, repetition, acceptedPerSecond, acceptP99 == null ? null : acceptP99 / 1000,
                    finish[0], finish[1], run.check(), Observations.of(recorder, waits.samples()), note)
                    .withoutNumbersIfFailed();
        }
    }

    /** Accepts at the condition's rate for its duration. Returns how many acceptances finished within it. */
    private static int pace(BenchEnvironment.Run run, Condition condition, long start, List<Accepted> accepted)
            throws InterruptedException {
        ExecutorService threads = Executors.newFixedThreadPool(ACCEPT_THREADS);
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        long end = start + condition.duration().toNanos();
        int scheduled = 0;
        while (System.nanoTime() < end) {
            long due = (long) (condition.rate() * (System.nanoTime() - start) / 1e9);
            for (; scheduled < due; scheduled++) {
                threads.execute(() -> {
                    int index = next.getAndIncrement();
                    long began = System.nanoTime();
                    long took = run.acceptOne(index, condition.keys());
                    synchronized (accepted) {
                        accepted.add(new Accepted(began, took));
                    }
                    // Only acceptances that finished within the duration count toward the achieved rate
                    if (System.nanoTime() <= end) {
                        done.incrementAndGet();
                    }
                });
            }
            Thread.sleep(TICK);
        }
        threads.shutdown();
        // Requests still queued at the end are not part of the paced load
        threads.awaitTermination(1, TimeUnit.MINUTES);
        return done.get();
    }
}
