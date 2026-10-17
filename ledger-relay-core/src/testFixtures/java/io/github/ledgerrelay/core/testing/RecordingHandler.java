package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import java.util.ArrayList;
import java.util.List;

/** 결과 반영 핸들러 대역. 끝까지 실행된 호출만 기록한다. */
public final class RecordingHandler implements LedgerOutcomeHandler {

    private final List<CommandStatus> completed = new ArrayList<>();

    private int failuresLeft;
    private boolean crashNext;

    /** 다음 times번의 호출은 기록하기 전에 {@link HandlerFailure}를 던진다. */
    public synchronized void failNextCalls(int times) {
        failuresLeft = times;
    }

    /** 다음 호출은 기록하기 전에 {@link HandlerCrash}(Error)를 던진다. 메모리 부족 같은 JVM 수준 문제를 흉내 낸다. */
    public synchronized void crashNextCall() {
        crashNext = true;
    }

    @Override
    public synchronized void onCompleted(CommandStatus outcome) {
        if (crashNext) {
            crashNext = false;
            throw new HandlerCrash(outcome.commandId());
        }
        if (failuresLeft > 0) {
            failuresLeft--;
            throw new HandlerFailure(outcome.commandId());
        }
        completed.add(outcome);
    }

    /** 일부러 낸 핸들러 실패. */
    public static final class HandlerFailure extends RuntimeException {
        HandlerFailure(String commandId) {
            super("결과 반영 핸들러가 일부러 실패했다: " + commandId);
        }
    }

    /** 일부러 낸 JVM 수준 실패. */
    public static final class HandlerCrash extends Error {
        HandlerCrash(String commandId) {
            super("결과 반영 핸들러가 일부러 Error를 던졌다: " + commandId);
        }
    }

    /** 이 명령에 대해 끝까지 실행된 호출들. */
    public synchronized List<CommandStatus> completedFor(String commandId) {
        return completed.stream().filter(outcome -> outcome.commandId().equals(commandId)).toList();
    }
}
