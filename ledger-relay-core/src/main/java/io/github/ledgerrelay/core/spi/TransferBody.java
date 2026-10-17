package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * TB 이체 본문(스펙 §5.6). 접수 때 정해지고 바뀌지 않는다. 접수 때 검증했으므로 여기서는 다시 검증하지 않는다.
 * 검증 규칙이 나중에 강해져도 이미 접수된 명령이 막히지 않게 하기 위해서다.
 *
 * @param pendingTransferId post·void가 참조한 예약의 TB 이체 ID. 접수 때 참조 검사에서 정해진다(P2a-20). 그 밖에는 없다
 */
public record TransferBody(
        CommandKind kind,
        BigInteger debitAccountId,
        BigInteger creditAccountId,
        BigInteger amount,
        long ledger,
        int code,
        long timeoutSeconds,
        Optional<BigInteger> pendingTransferId,
        long userData64,
        int userData32) {

    public TransferBody {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(debitAccountId, "debitAccountId");
        Objects.requireNonNull(creditAccountId, "creditAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(pendingTransferId, "pendingTransferId");
    }

    public static TransferBody of(LedgerCommand command, Optional<BigInteger> pendingTransferId) {
        return new TransferBody(command.kind(), command.debitAccountId(), command.creditAccountId(), command.amount(),
                command.ledger(), command.code(), command.timeoutSeconds(), pendingTransferId,
                command.userData64(), command.userData32());
    }
}
