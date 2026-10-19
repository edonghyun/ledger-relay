package io.github.ledgerrelay.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Correctness check of a measurement run — a fast run that loses or doubles money is a failed run")
class CorrectnessTest {

    static BenchEnvironment environment;
    BenchEnvironment.Run run;

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

    @BeforeEach
    void givenAFreshRun() {
        run = environment.reset();
    }

    @Test
    @DisplayName("A run where every command was applied once passes")
    void aCleanRunPasses() throws Exception {
        // given
        run.accept(50, KeySpread.SPREAD);
        run.processAll(2, 32, Duration.ofMinutes(1));

        // when
        Correctness.Verdict verdict = run.check();

        // then
        assertThat(verdict.passed()).as("verdict " + verdict).isTrue();
        assertThat(verdict.applied()).as("applied commands").isEqualTo(50);
    }

    @Test
    @DisplayName("One transfer more in TB than applied commands fails the run — the check sees a double charge (control)")
    void anExtraTransferFailsTheRun() throws Exception {
        // given
        run.accept(50, KeySpread.SPREAD);
        run.processAll(2, 32, Duration.ofMinutes(1));
        run.transferOutsideTheRelay();

        // when
        Correctness.Verdict verdict = run.check();

        // then
        assertThat(verdict.transfersInLedger()).as("transfers in TB").isEqualTo(51);
        assertThat(verdict.passed()).as("verdict " + verdict).isFalse();
    }

    @Test
    @DisplayName("Commands that never finished fail the run — the check sees a missing payment (control)")
    void anUnfinishedCommandFailsTheRun() throws Exception {
        // given: accepted, but no worker ran
        run.accept(50, KeySpread.SPREAD);

        // when
        Correctness.Verdict verdict = run.check();

        // then
        assertThat(verdict.unfinished()).as("unfinished commands").isEqualTo(50);
        assertThat(verdict.passed()).as("verdict " + verdict).isFalse();
    }
}
