package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import java.util.ArrayList;
import java.util.List;

/** 결과 반영 핸들러 대역. 끝까지 실행된 호출만 기록한다. */
public final class RecordingHandler implements LedgerOutcomeHandler {

    private final List<CommandStatus> completed = new ArrayList<>();

    private int failuresLeft;

    /** 다음 times번의 호출은 기록하기 전에 {@link HandlerFailure}를 던진다. */
    public synchronized void failNextCalls(int times) {
        failuresLeft = times;
    }

    @Override
    public synchronized void onCompleted(CommandStatus outcome) {
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

    /** 이 명령에 대해 끝까지 실행된 호출들. */
    public synchronized List<CommandStatus> completedFor(String commandId) {
        return completed.stream().filter(outcome -> outcome.commandId().equals(commandId)).toList();
    }
}
