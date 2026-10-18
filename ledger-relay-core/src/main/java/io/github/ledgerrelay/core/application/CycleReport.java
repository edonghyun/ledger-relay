package io.github.ledgerrelay.core.application;

/**
 * 워커 한 바퀴에서 한 일. spring-boot 모듈의 루프({@code RelayWorkerLoop})는 {@link #IDLE}이면 잠깐 쉬고, 아니면 바로 다음 바퀴를 돈다.
 *
 * @param claimed   잡은 명령 수(멈춘 명령은 빼고)
 * @param submitted TB 요청에 담아 보낸 이체 수
 * @param completed 최종 상태로 쓰인 명령 수(건너뜀, 아는 결과 포함)
 * @param released  응답이 없거나 실패해 되돌린 명령 수
 */
public record CycleReport(int claimed, int submitted, int completed, int released) {

    public static final CycleReport IDLE = new CycleReport(0, 0, 0, 0);
}
