package io.github.ledgerrelay.shop.status;

import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.spring.LoggingWorkerEvents;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Logs the library's events and also counts the commands this instance sent to TB. The Workers box in the UI shows the
 * count per instance, which makes it visible that the two workers split the work. When the app provides this bean, the
 * library does not create its own.
 */
@Component
class CountingWorkerEvents implements WorkerEvents {

    private final WorkerEvents log = new LoggingWorkerEvents();
    private final AtomicLong submitted = new AtomicLong();

    long submitted() {
        return submitted.get();
    }

    @Override
    public void submitted(int batchSize) {
        submitted.addAndGet(batchSize);
        log.submitted(batchSize);
    }

    @Override
    public void halted(String commandId) {
        log.halted(commandId);
    }

    @Override
    public void malformedCandidate(String commandId, RuntimeException cause) {
        log.malformedCandidate(commandId, cause);
    }

    @Override
    public void fenced(String commandId) {
        log.fenced(commandId);
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        log.writeFailed(commandId, cause);
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        log.handlerFailed(commandId, cause);
    }

    @Override
    public void submitTimedOut(int batchSize) {
        log.submitTimedOut(batchSize);
    }

    @Override
    public void submitFailed(int batchSize, Throwable cause) {
        log.submitFailed(batchSize, cause);
    }

    @Override
    public void ledgerUnavailable(Throwable cause) {
        log.ledgerUnavailable(cause);
    }

    @Override
    public void malformedReply(int expected, int actual) {
        log.malformedReply(expected, actual);
    }

    @Override
    public void noSlots() {
        log.noSlots();
    }
}
