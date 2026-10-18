package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import java.util.ArrayList;
import java.util.List;

/** A stand-in outcome handler. It records only calls that ran to completion. */
public final class RecordingHandler implements LedgerOutcomeHandler {

    private final List<CommandStatus> completed = new ArrayList<>();

    private int failuresLeft;
    private boolean crashNext;

    /** The next times calls throw {@link HandlerFailure} before recording. */
    public synchronized void failNextCalls(int times) {
        failuresLeft = times;
    }

    /**
     * The next call throws {@link HandlerCrash} (an Error) before recording. Models a JVM-level problem such as out of
     * memory.
     */
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

    /** A deliberate handler failure. */
    public static final class HandlerFailure extends RuntimeException {
        HandlerFailure(String commandId) {
            super("The outcome handler failed on purpose: " + commandId);
        }
    }

    /** A deliberate JVM-level failure. */
    public static final class HandlerCrash extends Error {
        HandlerCrash(String commandId) {
            super("The outcome handler threw an Error on purpose: " + commandId);
        }
    }

    /** The calls for this command that ran to completion. */
    public synchronized List<CommandStatus> completedFor(String commandId) {
        return completed.stream().filter(outcome -> outcome.commandId().equals(commandId)).toList();
    }
}
