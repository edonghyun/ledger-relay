package io.github.ledgerrelay.core.application;

/**
 * What one worker cycle did. The loop in the spring-boot module ({@code RelayWorkerLoop}) pauses briefly on
 * {@link #IDLE}; otherwise it runs the next cycle right away.
 *
 * @param claimed   the number of commands claimed (halted commands excluded)
 * @param submitted the number of transfers sent in TB requests
 * @param completed the number of commands written to a final state (including skipped and known results)
 * @param released  the number of commands put back after no response or a failure
 */
public record CycleReport(int claimed, int submitted, int completed, int released) {

    public static final CycleReport IDLE = new CycleReport(0, 0, 0, 0);
}
