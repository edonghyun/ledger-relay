package io.github.ledgerrelay.bench;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Worker events of one run, kept for the bottleneck notes. Shared by all workers of the run. */
final class Recorder implements WorkerEvents {

    private final List<Long> submitNanos = new ArrayList<>();
    final LongAdder transfersSent = new LongAdder();
    final LongAdder requests = new LongAdder();
    final LongAdder failedRequests = new LongAdder();
    final LongAdder noSlots = new LongAdder();
    final LongAdder fenced = new LongAdder();
    final LongAdder handlerFailures = new LongAdder();
    final LongAdder writeFailures = new LongAdder();
    final LongAdder cycles = new LongAdder();
    final LongAdder idleCycles = new LongAdder();
    final LongAdder finished = new LongAdder();

    @Override
    public void submitCompleted(int batchSize, Duration elapsed, SubmitOutcome outcome) {
        requests.increment();
        transfersSent.add(batchSize);
        if (outcome != SubmitOutcome.REPLIED) {
            failedRequests.increment();
        }
        synchronized (submitNanos) {
            submitNanos.add(elapsed.toNanos());
        }
    }

    @Override
    public void finished(CommandStatus outcome) {
        finished.increment();
    }

    @Override
    public void noSlots() {
        noSlots.increment();
    }

    @Override
    public void fenced(String commandId) {
        fenced.increment();
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        handlerFailures.increment();
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        writeFailures.increment();
    }

    List<Long> submitNanos() {
        synchronized (submitNanos) {
            return List.copyOf(submitNanos);
        }
    }
}
