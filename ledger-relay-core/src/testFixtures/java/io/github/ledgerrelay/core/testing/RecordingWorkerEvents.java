package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in that keeps worker events as "name:argument" strings in the order received. For example {@code
 * "halted:capture-1"}, {@code "submitted:3"}.
 */
public final class RecordingWorkerEvents implements WorkerEvents {

    private final java.util.Map<String, RuntimeException> writeFailures = new java.util.HashMap<>();

    private final List<String> events = new ArrayList<>();
    private final List<CommandStatus> finished = new ArrayList<>();
    private final List<SubmitCompleted> submitsCompleted = new ArrayList<>();

    /** One {@link #submitCompleted} call. */
    public record SubmitCompleted(int batchSize, Duration elapsed, SubmitOutcome outcome) {
    }

    /** The outcomes reported by {@link #finished}, in order. */
    public synchronized List<CommandStatus> finished() {
        return List.copyOf(finished);
    }

    /** The {@link #submitCompleted} calls, in order. */
    public synchronized List<SubmitCompleted> submitsCompleted() {
        return List.copyOf(submitsCompleted);
    }

    @Override
    public synchronized void finished(CommandStatus outcome) {
        finished.add(outcome);
    }

    @Override
    public synchronized void submitCompleted(int batchSize, Duration elapsed, SubmitOutcome outcome) {
        submitsCompleted.add(new SubmitCompleted(batchSize, elapsed, outcome));
    }

    public synchronized List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public synchronized void halted(String commandId) {
        events.add("halted:" + commandId);
    }

    @Override
    public synchronized void malformedCandidate(String commandId, RuntimeException cause) {
        events.add("malformedCandidate:" + commandId);
    }

    @Override
    public synchronized void fenced(String commandId) {
        events.add("fenced:" + commandId);
    }

    @Override
    public synchronized void writeFailed(String commandId, RuntimeException cause) {
        events.add("writeFailed:" + commandId);
        writeFailures.put(commandId, cause);
    }

    /** The exception of the last write failure for that command. */
    public synchronized RuntimeException writeFailure(String commandId) {
        RuntimeException cause = writeFailures.get(commandId);
        if (cause == null) {
            throw new AssertionError("No write failure for " + commandId + ". Events: " + events);
        }
        return cause;
    }

    @Override
    public synchronized void handlerFailed(String commandId, RuntimeException cause) {
        events.add("handlerFailed:" + commandId);
    }

    @Override
    public synchronized void submitted(int batchSize) {
        events.add("submitted:" + batchSize);
    }

    @Override
    public synchronized void submitTimedOut(int batchSize) {
        events.add("submitTimedOut:" + batchSize);
    }

    @Override
    public synchronized void submitFailed(int batchSize, Throwable cause) {
        events.add("submitFailed:" + batchSize);
    }

    @Override
    public synchronized void ledgerUnavailable(Throwable cause) {
        events.add("ledgerUnavailable");
    }

    @Override
    public synchronized void malformedReply(int expected, int actual) {
        events.add("malformedReply:" + expected + ":" + actual);
    }

    @Override
    public synchronized void noSlots() {
        events.add("noSlots");
    }
}
