package io.github.ledgerrelay.core;

/** The caller referred to a command that was never accepted. */
public class CommandNotFoundException extends RuntimeException {

    private final String commandId;

    public CommandNotFoundException(String commandId) {
        super("No command was accepted with this id: " + commandId);
        this.commandId = commandId;
    }

    public String commandId() {
        return commandId;
    }
}
