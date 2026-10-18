package io.github.ledgerrelay.core;

/** 같은 commandId로 다른 내용의 명령이 이미 접수되어 있다(G7). 처음 접수한 내용은 그대로 남는다. */
public class CommandConflictException extends RuntimeException {

    private final String commandId;

    public CommandConflictException(String commandId) {
        super("같은 commandId(" + commandId + ")로 다른 내용의 명령이 이미 접수되어 있습니다. "
                + "같은 업무 의도에는 같은 내용을 보내야 합니다");
        this.commandId = commandId;
    }

    public String commandId() {
        return commandId;
    }
}
