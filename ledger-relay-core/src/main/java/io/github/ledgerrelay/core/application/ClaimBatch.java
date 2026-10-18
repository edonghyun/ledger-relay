package io.github.ledgerrelay.core.application;

import java.util.List;

/**
 * The result of one claim.
 *
 * @param halted the commandIds of commands the gate halted (HALT). The row becomes HALTED and is never a candidate
 *               again, so each appears only once. The worker reports them (docs/failure-modes.md F9)
 * @param problems candidates whose decision threw, with the cause (F29). A candidate that was parked as halted also
 *                 appears in {@code halted}
 */
public record ClaimBatch(List<ClaimedCommand> claimed, List<String> halted, List<Problem> problems) {

    /**
     * A candidate whose decision threw (F29). The row data broke the rules, or the store returned a row that is not a
     * candidate.
     *
     * @param halted the row was parked as halted (HALTED). If false, even halting failed, so this claim passed over it
     *               (it shows up again in the next claim)
     */
    public record Problem(String commandId, RuntimeException cause, boolean halted) {
    }

    public ClaimBatch {
        claimed = List.copyOf(claimed);
        halted = List.copyOf(halted);
        problems = List.copyOf(problems);
    }

    public ClaimBatch(List<ClaimedCommand> claimed, List<String> halted) {
        this(claimed, halted, List.of());
    }

    public static ClaimBatch empty() {
        return new ClaimBatch(List.of(), List.of(), List.of());
    }
}
