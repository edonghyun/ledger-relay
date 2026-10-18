package io.github.ledgerrelay.core.spi;

/**
 * The TB client can no longer be used (closed, session evicted, client and server version mismatch). Resending does
 * not recover. An adapter wraps its own client's exception in this one to report it. The worker reports it separately
 * from transient failures ({@link WorkerEvents#ledgerUnavailable}) and puts the commands back. Whether to stop is up to
 * the caller (the loop and health in the spring-boot module).
 */
public final class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
