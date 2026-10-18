package io.github.ledgerrelay.core;

/** The result of abandoning a halted command. */
public enum AbandonOutcome {
    /** The command ended as skipped and the handler ran to completion. */
    ABANDONED,
    /** The handler threw and the transaction rolled back. The command stays halted and can be abandoned again. */
    HANDLER_FAILED
}
