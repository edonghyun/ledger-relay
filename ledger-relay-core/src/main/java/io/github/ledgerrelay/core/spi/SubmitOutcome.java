package io.github.ledgerrelay.core.spi;

/** How one TB request ended ({@link WorkerEvents#submitCompleted}). */
public enum SubmitOutcome {
    /** TB answered with one result per transfer. */
    REPLIED,
    /** No answer within submit-timeout. The request may still end inside the TB client. */
    TIMED_OUT,
    /** The request ended with an exception, or sending it threw on the spot. */
    FAILED,
    /** The TB client can no longer be used ({@link LedgerUnavailableException}). */
    LEDGER_UNAVAILABLE,
    /** TB answered with a different number of results than transfers sent. */
    MALFORMED_REPLY
}
