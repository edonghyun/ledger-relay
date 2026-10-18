package io.github.ledgerrelay.core;

/**
 * The reservation that a post or void refers to is not a reserve command already committed under the same ordering
 * key. A reservation just accepted in the same app transaction is not committed yet, so it also counts here.
 */
public class ReservationNotFoundException extends RuntimeException {

    private final String commandId;
    private final String reservationCommandId;

    public ReservationNotFoundException(String commandId, String reservationCommandId, String orderingKey) {
        super("The reservation " + reservationCommandId + " referred to by " + commandId
                + " is not a reserve command already committed under the same ordering key (" + orderingKey + ")");
        this.commandId = commandId;
        this.reservationCommandId = reservationCommandId;
    }

    public String commandId() {
        return commandId;
    }

    public String reservationCommandId() {
        return reservationCommandId;
    }
}
