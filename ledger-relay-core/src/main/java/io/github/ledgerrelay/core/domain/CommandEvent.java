package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.ResultCategory;

/**
 * Events of the transition table. A claim splits into open and closed by the gate decision; a skip is an event after
 * the claim.
 */
public enum CommandEvent {
    /** claim: the gate is open. */
    CLAIM_WITH_GATE_OPEN,
    /** claim: the gate is closed because an earlier command of the same key is unfinished. */
    CLAIM_WITH_GATE_CLOSED,
    /** claim: the gate halted (HALT) because the input cannot occur on the normal path. */
    CLAIM_WITH_GATE_HALTED,
    /**
     * Records the submission right before sending to TB (submit_count += 1, and first_submitted_at the first time).
     * It is recorded before the send, so "it was sent" survives even if the response is lost (in progress). With a
     * known_result nothing is sent, so this event does not occur either.
     */
    SUBMITTING,
    /**
     * The reservation referenced by a claimed post or void ended in a final state other than APPLIED. Skip without
     * sending to TB.
     */
    RESERVATION_NOT_APPLIED,
    /** TB success (created, exists). */
    TB_APPLIED,
    /** A definite TB failure (business rejection, prior failure, invalid request). */
    TB_REJECTED,
    /** A TB content conflict (exists_with_different_*) and codes not applicable to v1. */
    TB_CONFLICT,
    /** No response within submit-timeout, or an exception. */
    NO_REPLY_OR_ERROR,
    /** The outcome handler threw an exception. */
    HANDLER_FAILED,
    /** Another worker claims again a command whose lease expired. */
    LEASE_EXPIRED,
    /** A write with a lease token that has already moved on (a late worker). */
    STALE_TOKEN_WRITE,
    /** An operator abandons a halted command. It ends as skipped without being sent to TB. */
    ABANDONED_BY_OPERATOR;

    /** Maps a TB result category to the TB event of the transition table. */
    public static CommandEvent of(ResultCategory category) {
        return switch (category) {
            case APPLIED -> TB_APPLIED;
            case CONFLICT, UNSUPPORTED -> TB_CONFLICT;
            case BUSINESS, PRIOR_FAILURE, INVALID -> TB_REJECTED;
        };
    }
}
