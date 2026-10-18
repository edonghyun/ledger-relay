package io.github.ledgerrelay.core.application;

import java.util.List;

/**
 * claim 한 번의 결과.
 *
 * @param halted 게이트가 멈춘(HALT) 명령의 commandId. 행은 HALTED로 바뀌어 다시 후보가 되지 않으므로 한 번만 실린다.
 *               워커가 알린다(docs/failure-modes.md F9)
 * @param problems 판단이 예외를 던진 후보와 그 이유(F29). 멈춤으로 세운 후보는 {@code halted}에도 실린다
 */
public record ClaimBatch(List<ClaimedCommand> claimed, List<String> halted, List<Problem> problems) {

    /**
     * 판단이 예외를 던진 후보(F29). 행의 데이터가 규칙과 어긋났거나 저장소가 후보가 아닌 행을 냈다.
     *
     * @param halted 그 행을 멈춤(HALTED)으로 세웠다. false면 멈추게도 못 해 이번 claim에서 건너뛰었다(다음 claim에 또 나온다)
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
