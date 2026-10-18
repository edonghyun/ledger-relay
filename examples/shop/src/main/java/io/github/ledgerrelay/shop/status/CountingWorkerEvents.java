package io.github.ledgerrelay.shop.status;

import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.spring.LoggingWorkerEvents;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 라이브러리의 로그 이벤트에 더해 이 인스턴스가 TB에 보낸 명령 수를 센다. 화면의 Workers 칸이 인스턴스마다 보낸 수를 보여 워커
 * 둘이 나눠 일하는 것을 드러낸다. 앱이 이 빈을 주면 라이브러리는 자기 것을 만들지 않는다.
 */
@Component
class CountingWorkerEvents implements WorkerEvents {

    private final WorkerEvents log = new LoggingWorkerEvents();
    private final AtomicLong submitted = new AtomicLong();

    long submitted() {
        return submitted.get();
    }

    @Override
    public void submitted(int batchSize) {
        submitted.addAndGet(batchSize);
        log.submitted(batchSize);
    }

    @Override
    public void halted(String commandId) {
        log.halted(commandId);
    }

    @Override
    public void malformedCandidate(String commandId, RuntimeException cause) {
        log.malformedCandidate(commandId, cause);
    }

    @Override
    public void fenced(String commandId) {
        log.fenced(commandId);
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        log.writeFailed(commandId, cause);
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        log.handlerFailed(commandId, cause);
    }

    @Override
    public void submitTimedOut(int batchSize) {
        log.submitTimedOut(batchSize);
    }

    @Override
    public void submitFailed(int batchSize, Throwable cause) {
        log.submitFailed(batchSize, cause);
    }

    @Override
    public void ledgerUnavailable(Throwable cause) {
        log.ledgerUnavailable(cause);
    }

    @Override
    public void malformedReply(int expected, int actual) {
        log.malformedReply(expected, actual);
    }

    @Override
    public void noSlots() {
        log.noSlots();
    }
}
