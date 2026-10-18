package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * The TB transfer body. It is set at acceptance and never changes. It was validated at acceptance, so it is not
 * validated again here. That way, commands already accepted are not blocked if the validation rules get stricter later.
 *
 * @param pendingTransferId the TB transfer id of the reservation the post or void refers to. Set by the reference
 *                          check at acceptance. Absent otherwise
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
