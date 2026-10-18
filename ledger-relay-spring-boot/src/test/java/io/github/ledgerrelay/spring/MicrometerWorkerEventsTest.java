package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.Rejection;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Micrometer metrics from worker events")
class MicrometerWorkerEventsTest {

    static final Instant ACCEPTED = Instant.parse("2026-10-19T00:00:00Z");

    MeterRegistry registry;
    MicrometerWorkerEvents metrics;

    @BeforeEach
    void givenAnEmptyRegistry() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerWorkerEvents(registry);
    }

    @Nested
    @Order(1)
    @DisplayName("When commands finish")
    class WhenCommandsFinish {

        @Test
        @DisplayName("They are counted by final state and, for rejections, by TB's result code")
        void finishedCommandsAreCountedByStateAndCode() {
            // when
            metrics.finished(applied("pay-1", Duration.ofMillis(300)));
            metrics.finished(applied("pay-2", Duration.ofMillis(1500)));
            metrics.finished(rejected("pay-3", LedgerResultCode.ExceedsCredits));

            // then
            assertFinishedCount("applied", "none", 2);
            assertFinishedCount("rejected", "ExceedsCredits", 1);
        }

        @Test
        @DisplayName("The time from acceptance to completion is recorded per command")
        void acceptToFinishTimeIsRecorded() {
            // when
            metrics.finished(applied("pay-1", Duration.ofMillis(300)));
            metrics.finished(applied("pay-2", Duration.ofMillis(1500)));

            // then
            assertThat(registry.get("ledger.command.duration").tag("state", "applied").timer().count())
                    .as("recorded durations").isEqualTo(2);
            assertThat(registry.get("ledger.command.duration").tag("state", "applied").timer().max(TimeUnit.MILLISECONDS))
                    .as("longest duration (ms)").isEqualTo(1500);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When TB requests end and other events arrive")
    class WhenRequestsEnd {

        @Test
        @DisplayName("Each request's duration is recorded by outcome, and its batch size is summed")
        void requestDurationAndBatchSizeAreRecorded() {
            // when
            metrics.submitCompleted(4, Duration.ofMillis(120), SubmitOutcome.REPLIED);

            // then
            assertThat(registry.get("ledger.submit.latency").tag("outcome", "replied").timer().count())
                    .as("recorded requests").isEqualTo(1);
            assertThat(registry.get("ledger.submit.batch.size").summary().totalAmount()).as("transfers sent")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("A fenced write, a handler failure and a cycle without slots each have their own counter")
        void problemsAreCounted() {
            // when
            metrics.fenced("pay-1");
            metrics.handlerFailed("pay-2", new IllegalStateException("handler"));
            metrics.noSlots();
            metrics.halted("capture-1");

            // then
            assertThat(registry.get("ledger.stale.writes.rejected").counter().count()).as("fenced writes").isEqualTo(1);
            assertThat(registry.get("ledger.handler.failures").counter().count()).as("handler failures").isEqualTo(1);
            assertThat(registry.get("ledger.worker.problems").tag("kind", "no_slots").counter().count())
                    .as("cycles without slots").isEqualTo(1);
            assertThat(registry.get("ledger.worker.problems").tag("kind", "halted").counter().count())
                    .as("halted commands").isEqualTo(1);
        }
    }

    private void assertFinishedCount(String state, String code, double expected) {
        assertThat(registry.get("ledger.commands.finished").tag("state", state).tag("code", code).counter().count())
                .as("finished commands state=%s code=%s", state, code).isEqualTo(expected);
    }

    private static CommandStatus applied(String commandId, Duration took) {
        return new CommandStatus(commandId, State.APPLIED, Optional.empty(), Optional.empty(), BigInteger.valueOf(101), 1,
                ACCEPTED, Optional.of(ACCEPTED.plus(took)));
    }

    private static CommandStatus rejected(String commandId, LedgerResultCode code) {
        return new CommandStatus(commandId, State.REJECTED, Optional.of(Rejection.of(code)), Optional.empty(),
                BigInteger.valueOf(102), 1, ACCEPTED, Optional.of(ACCEPTED.plusMillis(50)));
    }
}
