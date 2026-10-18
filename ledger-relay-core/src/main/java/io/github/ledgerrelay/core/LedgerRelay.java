package io.github.ledgerrelay.core;

import java.util.Optional;

/**
 * The incoming port of ledger-relay. This interface is all the app uses.
 * The Spring module assembles the implementation and exposes it as a bean.
 */
public interface LedgerRelay {

    /**
     * Accepts a command. Call it inside the app transaction (G1). If the app transaction rolls back, the acceptance is
     * gone too. Submitting the same command again returns the first receipt unchanged.
     *
     * @throws CommandConflictException     different content is already accepted under the same commandId (G7)
     * @throws ReservationNotFoundException the post or void does not refer to a reserve command already committed under
     *                                      the same key. A reservation accepted in the same app transaction also
     *                                      counts here
     * @throws InvalidCommandException      the command cannot be built, for example an id that contains U+0000 or a
     *                                      lone surrogate, or is longer than 512 bytes
     * @throws RuntimeException             called outside a transaction (G1). The transaction adapter decides the type:
     *                                      Spring's {@code IllegalTransactionStateException} for the jdbc adapter,
     *                                      {@code IllegalStateException} for the in-memory store
     */
    CommandReceipt submit(LedgerCommand command);

    /** The current state of the command. Empty if it was never accepted. */
    Optional<CommandStatus> status(String commandId);
}
