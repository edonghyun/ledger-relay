package io.github.ledgerrelay.core;

/** The kind of command. */
public enum CommandKind {
    TRANSFER,
    RESERVE,
    POST,
    VOID;

    /** post and void refer to a reservation command accepted earlier. */
    public boolean referencesReservation() {
        return this == POST || this == VOID;
    }
}
