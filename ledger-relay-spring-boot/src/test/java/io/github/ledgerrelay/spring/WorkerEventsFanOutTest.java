package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.core.testing.RecordingWorkerEvents;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker events fan out — logging, metrics and the app's own listener all receive each event")
class WorkerEventsFanOutTest {

    static final CommandStatus APPLIED = new CommandStatus("pay-1", State.APPLIED, Optional.empty(), Optional.empty(),
            BigInteger.valueOf(101), 1, Instant.parse("2026-10-19T00:00:00Z"),
            Optional.of(Instant.parse("2026-10-19T00:00:01Z")));

    @Test
    @DisplayName("Every listener receives each event")
    void everyListenerReceivesEachEvent() {
        // given
        RecordingWorkerEvents first = new RecordingWorkerEvents();
        RecordingWorkerEvents second = new RecordingWorkerEvents();
        WorkerEvents fanOut = new WorkerEventsFanOut(List.of(first, second));

        // when
        fanOut.finished(APPLIED);
        fanOut.submitCompleted(3, Duration.ofMillis(5), SubmitOutcome.REPLIED);
        fanOut.fenced("pay-2");

        // then
        assertReceivedEverything(first);
        assertReceivedEverything(second);
    }

    @Test
    @DisplayName("A listener that throws does not stop the others, and the worker sees no exception — otherwise a metrics bug would break the worker cycle (F46)")
    void aThrowingListenerDoesNotStopTheOthers() {
        // given
        RecordingWorkerEvents healthy = new RecordingWorkerEvents();
        WorkerEvents fanOut = new WorkerEventsFanOut(List.of(new ThrowingEvents(), healthy));

        // when
        fanOut.finished(APPLIED);
        fanOut.submitCompleted(3, Duration.ofMillis(5), SubmitOutcome.REPLIED);
        fanOut.fenced("pay-2");

        // then
        assertReceivedEverything(healthy);
    }

    private static void assertReceivedEverything(RecordingWorkerEvents listener) {
        assertThat(listener.finished()).as("finished commands received").containsExactly(APPLIED);
        assertThat(listener.submitsCompleted()).as("TB requests received").hasSize(1);
        assertThat(listener.events()).as("other events received").containsExactly("fenced:pay-2");
    }

    /** A listener with a bug: every method throws. */
    private static final class ThrowingEvents implements WorkerEvents {

        @Override
        public void finished(CommandStatus outcome) {
            throw new IllegalStateException("bug in a listener");
        }

        @Override
        public void submitCompleted(int batchSize, Duration elapsed, SubmitOutcome outcome) {
            throw new IllegalStateException("bug in a listener");
        }

        @Override
        public void fenced(String commandId) {
            throw new IllegalStateException("bug in a listener");
        }
    }
}
