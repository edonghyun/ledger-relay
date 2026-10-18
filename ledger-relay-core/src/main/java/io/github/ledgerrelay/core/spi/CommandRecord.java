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
 * @param completedAt              끝나기 전에는 없다
 * @param acceptedInThisTransaction 이 행을 지금 부른 쪽의 트랜잭션이 넣었는가. 커밋된 뒤 다시 읽으면 false(P2b-2)
 * @param halt                     멈춘(HALTED) 행에만 있다
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
