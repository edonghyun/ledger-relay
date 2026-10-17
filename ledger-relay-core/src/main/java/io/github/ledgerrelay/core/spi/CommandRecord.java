package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.domain.CommandRow;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 저장된 명령 행(§5.6). lease_expires_at, next_attempt_at, first_submitted_at은 저장소가 claim 후보를 고를 때만
 * 쓰므로 싣지 않는다.
 *
 * @param row         상태 기계가 보는 부분
 * @param completedAt 끝나기 전에는 없다
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
        Optional<Instant> completedAt) {

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
    }

    public CommandKind kind() {
        return body.kind();
    }
}
