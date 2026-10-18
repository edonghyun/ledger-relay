package io.github.ledgerrelay.core;

/** Why a command halted (HALTED). The operator uses it to choose what to fix. */
public enum HaltReason {
    /**
     * The reservation that a post or void refers to is not among the commands of the same key. A bug or a direct DB
     * edit (F10).
     */
    RESERVATION_MISSING,
    /**
     * The referenced reservation does not have an earlier seq than the post or void. Acceptance checks should have
     * blocked this order (F9).
     */
    RESERVATION_NOT_EARLIER,
    /** The row data broke the rules, so no decision was possible (F29). */
    MALFORMED_ROW
}
