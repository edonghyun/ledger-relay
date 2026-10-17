package io.github.ledgerrelay.core.spi;

/**
 * 워커 한 바퀴에서 일어난 일을 바깥에 알리는 나가는 포트(03a P3a-7). 지표와 로그는 03b가 이것으로 붙인다.
 * 모든 메서드는 아무것도 하지 않는 기본 구현을 갖는다. 구현은 빨리 돌아와야 하고 던지지 않아야 한다.
 */
public interface WorkerEvents {

    /** 아무것도 하지 않는다. */
    WorkerEvents NONE = new WorkerEvents() {
    };

    /** 게이트가 멈춘 명령. 다시 후보가 되지 않으므로 명령마다 한 번만 온다(§5.2 HALT). */
    default void halted(String commandId) {
    }

    /** 쓰기가 0행이었다. 다른 워커가 가져갔거나 이미 끝났다(G5, stale_write_rejected). */
    default void fenced(String commandId) {
    }

    /** 결과 반영 핸들러가 던졌다. 받은 결과를 남기고 backoff 뒤 다시 잡힌다(§5.4). */
    default void handlerFailed(String commandId, RuntimeException cause) {
    }

    /** 이체 batchSize개를 한 요청으로 TB에 보냈다. */
    default void submitted(int batchSize) {
    }

    /** submit-timeout 안에 응답이 없어 batchSize개를 되돌렸다. */
    default void submitTimedOut(int batchSize) {
    }

    /** 요청이 예외로 끝나 batchSize개를 되돌렸다. */
    default void submitFailed(int batchSize, Throwable cause) {
    }

    /** 결과 개수가 요청과 달라 아무것도 쓰지 않고 되돌렸다. 버그 신호다. */
    default void malformedReply(int expected, int actual) {
    }

    /** in-flight 슬롯을 잡지 못해 이번 바퀴에는 claim하지 않았다. TB가 멈췄거나 느리다는 배압 신호다. */
    default void noSlots() {
    }
}
