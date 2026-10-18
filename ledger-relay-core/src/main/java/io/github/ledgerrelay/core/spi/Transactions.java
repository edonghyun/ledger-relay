package io.github.ledgerrelay.core.spi;

import java.util.function.Supplier;

/**
 * Transaction boundaries (G1, G6). jdbc implements them with Spring transactions, the in-memory store with snapshots.
 * Services handle transactions only through this port. {@link CommandStore} methods are always called inside a
 * transaction.
 */
public interface Transactions {

    /**
     * Runs work inside a transaction. It joins an active transaction if there is one; otherwise it opens a new one and
     * commits it (Spring REQUIRED). If work throws, a newly opened transaction rolls back and the exception is rethrown
     * unchanged.
     */
    <T> T inTransaction(Supplier<T> work);

    /**
     * Throws if the calling thread has no active transaction (G1), because acceptance must be bound to the app
     * transaction. The implementation decides the exception type. jdbc throws Spring's
     * IllegalTransactionStateException.
     */
    void requireActive();

    /**
     * Whether the calling thread has an active transaction. Worker steps must be called only outside a transaction, so
     * the service uses this to block them. Joining an outer transaction buries the rollback from a handler failure in
     * the outer one and breaks G6. In jdbc it is {@code TransactionSynchronizationManager.isActualTransactionActive()}.
     */
    boolean isActive();
}
