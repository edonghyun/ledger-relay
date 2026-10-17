package io.github.ledgerrelay.core.spi;

import java.math.BigInteger;
import java.util.Objects;

/**
 * TB 이체 하나로 보낼 내용(§5.3).
 *
 * @param userData128 명령 행 ID. TB에서 PG를 역참조한다(§5.6)
 */
public record Submission(BigInteger transferId, BigInteger userData128, TransferBody body) {

    public Submission {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(userData128, "userData128");
        Objects.requireNonNull(body, "body");
    }
}
