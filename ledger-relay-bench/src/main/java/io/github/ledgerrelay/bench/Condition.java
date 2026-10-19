package io.github.ledgerrelay.bench;

import java.time.Duration;

/**
 * One measurement condition.
 *
 * @param measure   {@code drain}: accept {@code commands} first, then time the workers emptying them. {@code steady}:
 *                  accept at {@code rate} per second for {@code duration} while the workers run
 * @param workers   workers, each with its own thread, in-flight slots, TB client and connection pool — like that
 *                  many app instances
 * @param warmup    steady only: commands accepted before this are left out of the latencies
 */
public record Condition(String measure, int workers, KeySpread keys, int batchSize, int commands, double rate,
        Duration duration, Duration warmup) {

    static Condition drain(int workers, KeySpread keys, int batchSize, int commands) {
        return new Condition("drain", workers, keys, batchSize, commands, 0, Duration.ZERO, Duration.ZERO);
    }

    static Condition steady(int workers, KeySpread keys, int batchSize, double rate, Duration duration, Duration warmup) {
        return new Condition("steady", workers, keys, batchSize, 0, rate, duration, warmup);
    }

    /** A short name for tables, for example {@code drain w4 spread b128}. */
    String label() {
        String base = measure + " w" + workers + " " + keys.name().toLowerCase() + " b" + batchSize;
        return measure.equals("steady") ? base + " @" + Math.round(rate) + "/s" : base;
    }
}
