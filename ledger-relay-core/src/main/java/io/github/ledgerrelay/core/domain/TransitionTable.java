package io.github.ledgerrelay.core.domain;

import static io.github.ledgerrelay.core.domain.Transition.Effect.BACKOFF;
import static io.github.ledgerrelay.core.domain.Transition.Effect.COUNT_HANDLER_FAILURE;
import static io.github.ledgerrelay.core.domain.Transition.Effect.INCREMENT_LEASE_TOKEN;
import static io.github.ledgerrelay.core.domain.Transition.Effect.KEEP_KNOWN_RESULT;
import static io.github.ledgerrelay.core.domain.Transition.Effect.RECORD_SUBMISSION;
import static io.github.ledgerrelay.core.domain.Transition.Effect.RUN_HANDLER;

/**
 * The transition table. States only move forward (G5), and entering a final state runs the handler in the same
 * transaction (G6). Every cell maps 1:1 to the table in {@code TransitionTableTest}.
 */
public final class TransitionTable {

    private TransitionTable() {
    }

    public static Transition next(StoredState current, CommandEvent event) {
        return switch (current) {
            case PENDING -> fromPending(event);
            case CLAIMED -> fromClaimed(event);
            case APPLIED, REJECTED, CONFLICT, SKIPPED -> fromFinal(event);
            case HALTED -> fromHalted(event);
        };
    }

    private static Transition fromPending(CommandEvent event) {
        return switch (event) {
            case CLAIM_WITH_GATE_OPEN -> Transition.moveTo(StoredState.CLAIMED, INCREMENT_LEASE_TOKEN);
            case CLAIM_WITH_GATE_CLOSED -> Transition.unchanged();
            // A command to halt also bumps the token, like a claim. The operator's abandon writes conditionally on that
            // token
            case CLAIM_WITH_GATE_HALTED -> Transition.moveTo(StoredState.HALTED, INCREMENT_LEASE_TOKEN);
            case ABANDONED_BY_OPERATOR -> Transition.impossible();
            // A skip also happens after the claim. That way it is fenced by the lease token, and a handler failure
            // takes the CLAIMED cell.
            case SUBMITTING, RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR,
                    HANDLER_FAILED -> Transition.impossible();
            case LEASE_EXPIRED -> Transition.notApplicable();
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }

    private static Transition fromClaimed(CommandEvent event) {
        return switch (event) {
            // Claiming again while the lease is live is impossible. A reclaim after expiry is LEASE_EXPIRED.
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED, CLAIM_WITH_GATE_HALTED, ABANDONED_BY_OPERATOR ->
                    Transition.impossible();
            case SUBMITTING -> Transition.moveTo(StoredState.CLAIMED, RECORD_SUBMISSION);
            case RESERVATION_NOT_APPLIED -> Transition.moveTo(StoredState.SKIPPED, RUN_HANDLER);
            case TB_APPLIED -> Transition.moveTo(StoredState.APPLIED, RUN_HANDLER);
            case TB_REJECTED -> Transition.moveTo(StoredState.REJECTED, RUN_HANDLER);
            case TB_CONFLICT -> Transition.moveTo(StoredState.CONFLICT, RUN_HANDLER);
            case NO_REPLY_OR_ERROR -> Transition.moveTo(StoredState.PENDING, BACKOFF);
            case HANDLER_FAILED -> Transition.moveTo(StoredState.PENDING, BACKOFF, KEEP_KNOWN_RESULT, COUNT_HANDLER_FAILURE);
            case LEASE_EXPIRED -> Transition.moveTo(StoredState.CLAIMED, INCREMENT_LEASE_TOKEN);
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }

    private static Transition fromFinal(CommandEvent event) {
        return switch (event) {
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED, CLAIM_WITH_GATE_HALTED, ABANDONED_BY_OPERATOR ->
                    Transition.impossible();
            case SUBMITTING, RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR,
                    STALE_TOKEN_WRITE -> Transition.zeroRows();
            case HANDLER_FAILED, LEASE_EXPIRED -> Transition.notApplicable();
        };
    }

    /** A halted command is not a claim candidate and accepts only the operator's abandon. */
    private static Transition fromHalted(CommandEvent event) {
        return switch (event) {
            case ABANDONED_BY_OPERATOR -> Transition.moveTo(StoredState.SKIPPED, RUN_HANDLER);
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED, CLAIM_WITH_GATE_HALTED, SUBMITTING,
                    RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR ->
                    Transition.impossible();
            // If the handler fails during an abandon, it rolls back and the command stays halted. No TB result was
            // received, so there is nothing to record
            case HANDLER_FAILED, LEASE_EXPIRED -> Transition.notApplicable();
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }
}
