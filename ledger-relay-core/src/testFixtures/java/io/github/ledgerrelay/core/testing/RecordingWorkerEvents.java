package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.util.ArrayList;
import java.util.List;

/** 워커 이벤트를 "이름:인자" 문자열로 받은 순서대로 남기는 대역. 예: {@code "halted:capture-1"}, {@code "submitted:3"}. */
public final class RecordingWorkerEvents implements WorkerEvents {

    private final List<String> events = new ArrayList<>();

    public synchronized List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public synchronized void halted(String commandId) {
        events.add("halted:" + commandId);
    }

    @Override
    public synchronized void fenced(String commandId) {
        events.add("fenced:" + commandId);
    }

    @Override
    public synchronized void handlerFailed(String commandId, RuntimeException cause) {
        events.add("handlerFailed:" + commandId);
    }

    @Override
    public synchronized void submitted(int batchSize) {
        events.add("submitted:" + batchSize);
    }

    @Override
    public synchronized void submitTimedOut(int batchSize) {
        events.add("submitTimedOut:" + batchSize);
    }

    @Override
    public synchronized void submitFailed(int batchSize, Throwable cause) {
        events.add("submitFailed:" + batchSize);
    }

    @Override
    public synchronized void malformedReply(int expected, int actual) {
        events.add("malformedReply:" + expected + ":" + actual);
    }

    @Override
    public synchronized void noSlots() {
        events.add("noSlots");
    }
}
