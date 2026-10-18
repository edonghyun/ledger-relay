package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.util.Objects;

/** The receipt from submit(). Submitting the same command again returns the first receipt unchanged. */
public record CommandReceipt(String commandId, BigInteger transferId) {

    public CommandReceipt {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(transferId, "transferId");
    }
}
