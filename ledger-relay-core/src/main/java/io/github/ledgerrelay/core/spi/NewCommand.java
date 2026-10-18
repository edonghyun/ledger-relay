package io.github.ledgerrelay.core.spi;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * A command row to insert. The service sets the seq, transfer id and content hash. The store sets the row id and
 * acceptance time.
 */
public record NewCommand(
        String commandId,
        String orderingKey,
        long seq,
        Optional<String> reservationCommandId,
        BigInteger transferId,
        String contentHash,
        TransferBody body) {

    public NewCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(orderingKey, "orderingKey");
        Objects.requireNonNull(reservationCommandId, "reservationCommandId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(body, "body");
    }
}
