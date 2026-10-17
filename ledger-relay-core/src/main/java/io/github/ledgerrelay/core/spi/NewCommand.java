package io.github.ledgerrelay.core.spi;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** 넣을 명령 행(§5.1-4·5). 순번, 이체 ID, 내용 해시는 서비스가 정해서 넘긴다. 행 ID와 접수 시각은 저장소가 정한다. */
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
