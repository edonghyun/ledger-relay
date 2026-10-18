package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.CommandKind;
import java.math.BigInteger;
import java.util.Objects;

/**
 * One transfer stored in TB (a lookup result for reconciliation). {@code kind} comes from the TB flags: pending is
 * RESERVE, post-pending is POST, void-pending is VOID, and anything else is TRANSFER.
 */
public record LedgerTransfer(BigInteger transferId, BigInteger debitAccountId, BigInteger creditAccountId,
        BigInteger amount, long ledger, int code, CommandKind kind) {

    public LedgerTransfer {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(debitAccountId, "debitAccountId");
        Objects.requireNonNull(creditAccountId, "creditAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(kind, "kind");
    }
}
