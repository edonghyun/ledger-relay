package io.github.ledgerrelay.core;

/** The command cannot be abandoned. The reason is {@link #reason()}. */
public class CannotAbandonException extends RuntimeException {

    /** Why the command cannot be abandoned. */
    public enum Reason {
        /** The command is not halted (HALTED). */
        NOT_HALTED,
        /**
         * The command was already sent to TB, so TB may have applied it (F27). Check the transfer in TB, then clean up
         * by hand.
         */
        ALREADY_SUBMITTED
    }

    private final String commandId;
    private final Reason reason;

    public CannotAbandonException(String commandId, Reason reason, String message) {
        super(message);
        this.commandId = commandId;
        this.reason = reason;
    }

    public String commandId() {
        return commandId;
    }

    public Reason reason() {
        return reason;
    }
}
