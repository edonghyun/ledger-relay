package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Passes each worker event to every listener: logging, metrics and the app's own beans. A listener that throws is
 * logged and skipped, and the others still receive the event (F46): the worker calls this from its cycle, and an
 * exception escaping here would end the cycle with its commands still in flight.
 */
final class WorkerEventsFanOut implements WorkerEvents {

    private static final Log log = LogFactory.getLog(WorkerEventsFanOut.class);
    /** The same listener failing on the same event is logged at most once in this period. */
    private static final long LOG_EVERY_NANOS = Duration.ofMinutes(1).toNanos();

    private final List<WorkerEvents> listeners;
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    WorkerEventsFanOut(List<WorkerEvents> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    private void each(String event, Consumer<WorkerEvents> call) {
        for (WorkerEvents listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                logFailure(listener, event, e);
            }
        }
    }

    private void logFailure(WorkerEvents listener, String event, RuntimeException e) {
        String key = listener.getClass().getName() + "#" + event;
        long now = System.nanoTime();
        Long last = lastLogged.get(key);
        if (last == null || now - last >= LOG_EVERY_NANOS) {
            lastLogged.put(key, now);
            log.warn("Worker event listener " + listener.getClass().getName() + " threw on " + event
                    + ". The other listeners still received it", e);
        }
    }

    @Override
    public void halted(String commandId) {
        each("halted", listener -> listener.halted(commandId));
    }

    @Override
    public void malformedCandidate(String commandId, RuntimeException cause) {
        each("malformedCandidate", listener -> listener.malformedCandidate(commandId, cause));
    }

    @Override
    public void fenced(String commandId) {
        each("fenced", listener -> listener.fenced(commandId));
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        each("writeFailed", listener -> listener.writeFailed(commandId, cause));
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        each("handlerFailed", listener -> listener.handlerFailed(commandId, cause));
    }

    @Override
    public void submitted(int batchSize) {
        each("submitted", listener -> listener.submitted(batchSize));
    }

    @Override
    public void submitTimedOut(int batchSize) {
        each("submitTimedOut", listener -> listener.submitTimedOut(batchSize));
    }

    @Override
    public void submitFailed(int batchSize, Throwable cause) {
        each("submitFailed", listener -> listener.submitFailed(batchSize, cause));
    }

    @Override
    public void ledgerUnavailable(Throwable cause) {
        each("ledgerUnavailable", listener -> listener.ledgerUnavailable(cause));
    }

    @Override
    public void malformedReply(int expected, int actual) {
        each("malformedReply", listener -> listener.malformedReply(expected, actual));
    }

    @Override
    public void noSlots() {
        each("noSlots", WorkerEvents::noSlots);
    }

    @Override
    public void finished(CommandStatus outcome) {
        each("finished", listener -> listener.finished(outcome));
    }

    @Override
    public void submitCompleted(int batchSize, Duration elapsed, SubmitOutcome outcome) {
        each("submitCompleted", listener -> listener.submitCompleted(batchSize, elapsed, outcome));
    }
}
