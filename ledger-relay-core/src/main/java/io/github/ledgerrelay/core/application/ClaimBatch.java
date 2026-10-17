package io.github.ledgerrelay.core.application;

import java.util.List;

/**
 * claim 한 번의 결과.
 *
 * @param halted 게이트가 멈춘(HALT) 명령의 commandId. 행은 그대로 두며, 워커가 알린다(§5.2)
 */
public record ClaimBatch(List<ClaimedCommand> claimed, List<String> halted) {

    public ClaimBatch {
        claimed = List.copyOf(claimed);
        halted = List.copyOf(halted);
    }

    public static ClaimBatch empty() {
        return new ClaimBatch(List.of(), List.of());
    }
}
