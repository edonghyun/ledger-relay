package io.github.ledgerrelay.core.spi;

import java.math.BigInteger;
import java.util.Objects;

/**
 * What to send as one TB transfer.
 *
 * @param userData128 the command row id. It lets TB refer back to PG
 */
public record Submission(BigInteger transferId, BigInteger userData128, TransferBody body) {

    public Submission {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(userData128, "userData128");
        Objects.requireNonNull(body, "body");
    }
}
