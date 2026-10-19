package io.github.ledgerrelay.bench;

import java.time.Duration;
import java.time.Instant;

/**
 * Maximum throughput: accept all commands first with the workers off, then time the workers from start until the last
 * command's completion (both on the database clock).
 */
final class Drain {

    static final Duration LIMIT = Duration.ofMinutes(10);

    private Drain() {
    }

    static RunResult run(BenchEnvironment environment, Condition condition, int repetition) throws Exception {
        BenchEnvironment.Run run = environment.reset();
        run.accept(condition.commands(), condition.keys());
        Recorder recorder = new Recorder();
        Instant start;
        String note = "";
        try (WaitSampler waits = new WaitSampler(environment.admin())) {
            start = run.dbNow();
            try (Workers workers = Workers.start(run, condition.workers(), condition.batchSize(), recorder)) {
                if (!run.awaitFinished(LIMIT)) {
                    note = "not finished within " + LIMIT;
                }
            }
            Observations observations = Observations.of(recorder, waits.samples());
            double seconds = Duration.between(start, run.lastCompletion()).toNanos() / 1e9;
            return new RunResult(condition, repetition, condition.commands() / seconds, null, null, null, run.check(),
                    observations, note).withoutNumbersIfFailed();
        }
    }
}
