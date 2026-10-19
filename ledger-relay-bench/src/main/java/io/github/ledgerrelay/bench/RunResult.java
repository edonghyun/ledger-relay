package io.github.ledgerrelay.bench;

/**
 * One run of one condition. The numbers are null when the run failed its correctness check: a run that loses or
 * doubles money has no throughput worth reporting.
 *
 * @param completedPerSecond drain: commands finished per second. steady: commands accepted per second
 * @param acceptP99Micros    steady: p99 of one acceptance (the app's transaction that inserts the command)
 * @param note               for example "rate not sustained" when acceptance fell below 95% of the target
 */
public record RunResult(Condition condition, int repetition, Double completedPerSecond, Long acceptP99Micros,
        Long finishP50Millis, Long finishP99Millis, Correctness.Verdict verdict, Observations observations, String note) {

    RunResult withoutNumbersIfFailed() {
        return verdict.passed() ? this
                : new RunResult(condition, repetition, null, null, null, null, verdict, observations, note);
    }
}
