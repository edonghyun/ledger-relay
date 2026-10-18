package io.github.ledgerrelay.core;

/**
 * A command with different content is already accepted under the same commandId (G7). The first accepted content stays.
 */
public class CommandConflictException extends RuntimeException {

    private final String commandId;

    public CommandConflictException(String commandId) {
        super("A command with different content is already accepted under the same commandId (" + commandId + "). "
                + "Send the same content for the same business intent");
        this.commandId = commandId;
    }

    public String commandId() {
        return commandId;
    }
}
