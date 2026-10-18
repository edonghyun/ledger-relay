package io.github.ledgerrelay.tigerbeetle.testing;

import java.math.BigInteger;

/**
 * A transfer as TB actually stored it. Used to check the values that a post or void inherits from the reservation, and
 * the actual amount of a full-amount post.
 *
 * @param timestamp time (ns) at which the TB cluster applied the transfer
 */
public record StoredTransfer(BigInteger amount, byte[] debitAccountId, byte[] creditAccountId, int ledger, int code,
        BigInteger userData128, long userData64, int userData32, long timeoutSeconds, long timestamp) {
}
