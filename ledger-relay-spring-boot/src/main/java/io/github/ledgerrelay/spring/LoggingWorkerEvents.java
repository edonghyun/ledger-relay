package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.spi.WorkerEvents;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 워커 사건을 로그로 남긴다. 사람이 봐야 하는 사건(멈춤, 이상 후보, 쓰기 실패, 핸들러 실패, TB를 쓸 수 없음)은 WARN, 나머지는
 * DEBUG다. 지표는 03c에서 따로 붙인다.
 */
public final class LoggingWorkerEvents implements WorkerEvents {

    private static final Log log = LogFactory.getLog("io.github.ledgerrelay.worker");

    @Override
    public void halted(String commandId) {
        log.warn("명령 " + commandId + "가 멈췄다. 그 순서 키는 운영자가 풀 때까지 막힌다(LedgerRelayOperator)");
    }

    @Override
    public void malformedCandidate(String commandId, RuntimeException cause) {
        log.warn("claim 후보 " + commandId + "의 데이터가 규칙과 어긋난다", cause);
    }

    @Override
    public void fenced(String commandId) {
        log.debug("명령 " + commandId + "의 쓰기가 0행이었다(다른 워커가 가져갔거나 이미 끝났다)");
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        log.warn("명령 " + commandId + "의 결과를 쓰지 못했다. lease가 끝나면 같은 이체 ID로 다시 보낸다", cause);
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        log.warn("명령 " + commandId + "의 결과 반영 핸들러가 실패했다. backoff 뒤 다시 실행한다", cause);
    }

    @Override
    public void submitted(int batchSize) {
        log.debug("이체 " + batchSize + "개를 TB에 보냈다");
    }

    @Override
    public void submitTimedOut(int batchSize) {
        log.debug("TB가 submit-timeout 안에 답하지 않아 " + batchSize + "개를 되돌렸다");
    }

    @Override
    public void submitFailed(int batchSize, Throwable cause) {
        log.debug("TB 요청이 실패해 " + batchSize + "개를 되돌렸다", cause);
    }

    @Override
    public void ledgerUnavailable(Throwable cause) {
        log.warn("TB 클라이언트를 더 쓸 수 없다. 재시작이 필요할 수 있다", cause);
    }

    @Override
    public void malformedReply(int expected, int actual) {
        log.warn("TB 응답의 결과 수(" + actual + ")가 요청(" + expected + ")과 달라 아무것도 쓰지 않고 되돌렸다");
    }

    @Override
    public void noSlots() {
        log.debug("in-flight 슬롯을 잡지 못해 이번 바퀴는 claim하지 않았다");
    }
}
