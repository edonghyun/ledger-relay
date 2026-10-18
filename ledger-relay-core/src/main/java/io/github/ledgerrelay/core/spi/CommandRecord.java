package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.domain.CommandRow;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A stored command row. lease_expires_at, next_attempt_at and first_submitted_at are used only by the store to pick
 * claim candidates, so they are not carried.
 *
 * @param row         the part the state machine sees
 * @param completedAt              empty until the command ends
 * @param acceptedInThisTransaction whether the caller's current transaction inserted this row. False when read again
 *                                  after commit
 * @param halt                     present only on halted (HALTED) rows
 */
public record CommandRecord(
        long rowId,
        String commandId,
        String orderingKey,
        long seq,
        Optional<String> reservationCommandId,
        BigInteger transferId,
        String contentHash,
        TransferBody body,
        CommandRow row,
        Instant acceptedAt,
        Optional<Instant> completedAt,
        boolean acceptedInThisTransaction,
        Optional<HaltMark> halt) {

    public CommandRecord {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(orderingKey, "orderingKey");
        Objects.requireNonNull(reservationCommandId, "reservationCommandId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(halt, "halt");
    }

    public CommandKind kind() {
        return body.kind();
    }
}
