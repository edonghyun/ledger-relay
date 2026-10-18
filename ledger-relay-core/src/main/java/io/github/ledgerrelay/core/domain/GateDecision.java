package io.github.ledgerrelay.core.domain;

/** The decision of the ordering gate. */
public enum GateDecision {
    /** Submit. */
    OPEN,
    /** Wait for the earlier command of the same key. */
    CLOSED,
    /**
     * The reservation that the post or void refers to ended without being applied. After the claim, end as SKIPPED
     * without sending to TB.
     */
    SKIP,
    /** The input cannot occur on the normal path. Do not submit; halt and report. */
    HALT;

    /** Maps to the claim event of the transition table. */
    public CommandEvent claimEvent() {
        return switch (this) {
            // A command to skip is claimed first too. The skip (RESERVATION_NOT_APPLIED) is an event after the claim.
            case OPEN, SKIP -> CommandEvent.CLAIM_WITH_GATE_OPEN;
            case CLOSED -> CommandEvent.CLAIM_WITH_GATE_CLOSED;
            case HALT -> CommandEvent.CLAIM_WITH_GATE_HALTED;
        };
    }

    /** Whether a person needs to look at this decision. */
    public boolean alerts() {
        return this == HALT;
    }
}
