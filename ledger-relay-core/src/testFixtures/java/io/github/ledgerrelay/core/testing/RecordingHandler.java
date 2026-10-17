package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import java.util.ArrayList;
import java.util.List;

/** 결과 반영 핸들러 대역. 끝까지 실행된 호출만 기록한다. */
public final class RecordingHandler implements LedgerOutcomeHandler {

    private final List<CommandStatus> completed = new ArrayList<>();

    @Override
    public synchronized void onCompleted(CommandStatus outcome) {
        completed.add(outcome);
    }

    /** 이 명령에 대해 끝까지 실행된 호출들. */
    public synchronized List<CommandStatus> completedFor(String commandId) {
        return completed.stream().filter(outcome -> outcome.commandId().equals(commandId)).toList();
    }
}
