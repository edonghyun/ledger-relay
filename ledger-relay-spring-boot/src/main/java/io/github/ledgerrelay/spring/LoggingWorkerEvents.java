package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.spi.WorkerEvents;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Logs worker events. Events a person must look at (halt, malformed candidate, write failure, handler failure, TB
 * unusable) are WARN; the rest are DEBUG. Metrics are a separate listener ({@link MicrometerWorkerEvents}).
 */
public final class LoggingWorkerEvents implements WorkerEvents {

    private static final Log log = LogFactory.getLog("io.github.ledgerrelay.worker");

    @Override
    public void halted(String commandId) {
        log.warn("Command " + commandId + " halted. Its ordering key stays blocked until an operator releases it "
                + "(LedgerRelayOperator)");
    }

    @Override
    public void malformedCandidate(String commandId, RuntimeException cause) {
        log.warn("Claim candidate " + commandId + " has data that breaks the rules", cause);
    }

    @Override
    public void fenced(String commandId) {
        log.debug("Write for command " + commandId + " affected 0 rows (another worker took it, or it had already "
                + "finished)");
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        log.warn("Could not write the result of command " + commandId + ". It is resent with the same transfer id "
                + "after the lease ends", cause);
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        log.warn("The outcome handler for command " + commandId + " failed. It runs again after backoff", cause);
    }

    @Override
    public void submitted(int batchSize) {
        log.debug("Sent " + batchSize + " transfers to TB");
    }

    @Override
    public void submitTimedOut(int batchSize) {
        log.debug("TB did not answer within submit-timeout; returned " + batchSize + " commands");
    }

    @Override
    public void submitFailed(int batchSize, Throwable cause) {
        log.debug("TB request failed; returned " + batchSize + " commands", cause);
    }

    @Override
    public void ledgerUnavailable(Throwable cause) {
        log.warn("The TB client can no longer be used. A restart may be needed", cause);
    }

    @Override
    public void malformedReply(int expected, int actual) {
        log.warn("TB reply has " + actual + " results but the request had " + expected
                + "; wrote nothing and returned the commands");
    }

    @Override
    public void noSlots() {
        log.debug("Could not take an in-flight slot, so this cycle did not claim");
    }
}
