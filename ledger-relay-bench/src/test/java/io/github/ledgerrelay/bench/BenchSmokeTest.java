package io.github.ledgerrelay.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Measurements on a small scale — the code that produces docs/benchmarks.md runs and checks every run")
class BenchSmokeTest {

    static BenchEnvironment environment;

    @BeforeAll
    static void start() {
        environment = BenchEnvironment.start();
    }

    @AfterAll
    static void stop() {
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    @DisplayName("A small drain measures throughput and passes the correctness check")
    void aSmallDrainMeasuresAndChecks() throws Exception {
        // given
        Condition condition = Condition.drain(2, KeySpread.SPREAD, 32, 500);

        // when
        RunResult result = Drain.run(environment, condition, 1);

        // then
        assertThat(result.verdict().passed()).as("verdict " + result.verdict()).isTrue();
        assertThat(result.completedPerSecond()).as("completed per second").isPositive();
    }

    @Test
    @DisplayName("A small steady run on one key reports the accept-to-finish p99 and passes the check")
    void aSmallSteadyRunReportsLatency() throws Exception {
        // given
        Condition condition = Condition.steady(2, KeySpread.ONE, 32, 50, Duration.ofSeconds(3), Duration.ofSeconds(1));

        // when
        RunResult result = Steady.run(environment, condition, 1);

        // then
        assertThat(result.verdict().passed()).as("verdict " + result.verdict()).isTrue();
        assertThat(result.finishP99Millis()).as("accept-to-finish p99 (ms)").isNotNull();
        assertThat(result.acceptP99Micros()).as("accept p99 (µs)").isNotNull();
    }
}
