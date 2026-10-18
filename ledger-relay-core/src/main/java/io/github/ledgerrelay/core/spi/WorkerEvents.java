package io.github.ledgerrelay.core.spi;

/**
 * The outgoing port that reports what happened in a worker cycle. Logging ({@code LoggingWorkerEvents} in the
 * spring-boot module) and metrics attach through it. Every method has a default implementation that does nothing.
 * Implementations must return quickly and must not throw.
 */
public interface WorkerEvents {

    /** Does nothing. */
    WorkerEvents NONE = new WorkerEvents() {
    };

    /** A command the gate halted. It is never a candidate again, so this arrives only once per command. */
    default void halted(String commandId) {
    }

    /**
     * The decision for one claim candidate threw (F29). The row was halted, or even halting failed and it was passed
     * over. The other candidates were still processed. {@code cause} says what was wrong.
     */
    default void malformedCandidate(String commandId, RuntimeException cause) {
    }

    /** The write hit zero rows. Another worker took the command or it already ended (G5, stale_write_rejected). */
    default void fenced(String commandId) {
    }

    /**
     * The write for this command ended with an exception (a store error, for example). Only that command stays in
     * flight until its lease expires; the other commands in the same cycle keep being written. If a TB result had
     * already been received, that result is not recorded in this cycle.
     */
    default void writeFailed(String commandId, RuntimeException cause) {
    }

    /** The outcome handler threw. The received result is kept and the command is claimed again after the backoff. */
    default void handlerFailed(String commandId, RuntimeException cause) {
    }

    /** Sent batchSize transfers to TB in one request. */
    default void submitted(int batchSize) {
    }

    /** No response within submit-timeout, so batchSize commands were put back. */
    default void submitTimedOut(int batchSize) {
    }

    /** The request ended with an exception, so batchSize commands were put back. */
    default void submitFailed(int batchSize, Throwable cause) {
    }

    /**
     * The TB client can no longer be used ({@link LedgerUnavailableException}), so this request's commands were put
     * back. Counted separately from transient failures.
     */
    default void ledgerUnavailable(Throwable cause) {
    }

    /**
     * The result count differed from the request, so nothing was written and the commands were put back. A sign of a
     * bug.
     */
    default void malformedReply(int expected, int actual) {
    }

    /**
     * No in-flight slots could be taken, so this cycle did not claim. A backpressure signal that TB is stalled or slow.
     */
    default void noSlots() {
    }
}
