package io.github.ledgerrelay.core;

/** 접수한 적 없는 명령을 가리켰다. */
public class CommandNotFoundException extends RuntimeException {

    private final String commandId;

    public CommandNotFoundException(String commandId) {
        super("접수한 적 없는 명령입니다: " + commandId);
        this.commandId = commandId;
    }

    public String commandId() {
        return commandId;
    }
}
