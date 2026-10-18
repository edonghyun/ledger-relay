package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.CommandKind;
import java.math.BigInteger;
import java.util.Objects;

/**
 * TB에 저장된 이체 하나(대사용 조회 결과). {@code kind}는 TB flags에서 정한다: 보류는 RESERVE, 보류 확정은 POST, 보류 해제는
 * VOID, 그 밖에는 TRANSFER.
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
