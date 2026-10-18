package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Duration;

/** One set of storage for the contract tests to run against. Each implementation (in-memory, PostgreSQL) builds one. */
public interface StorageUnderTest {

    OrderingKeys keys();

    CommandStore store();

    Transactions transactions();

    /**
     * Moves the store's clock forward (lease expiry, backoff elapsed). The PG implementation shifts the timestamp
     * columns back by that much.
     */
    void elapse(Duration duration);

    /**
     * Test only: deletes one command row. Models a row gone through the retention job or a direct DB edit. Call it
     * outside a
     * transaction.
     */
    void deleteCommand(String commandId);

    /** The exception type thrown when {@link Transactions#requireActive()} is called outside a transaction. */
    Class<? extends RuntimeException> outsideTransactionError();

    /**
     * Whether transactions lock each other row by row. The in-memory store queues every transaction behind one lock, so
     * it
     * is false, and the concurrency sections are skipped.
     */
    default boolean supportsRowLocks() {
        return false;
    }

    /**
     * Whether the clock stands still between {@link #elapse(Duration)} calls. The in-memory store has a manual clock,
     * so it is
     * true. PostgreSQL's {@code now()} follows real time per transaction, so it is false, and the "exactly at that
     * time"
     * boundary tests are skipped.
     */
    default boolean clockStandsStill() {
        return true;
    }

    /**
     * Whether rows that break the rules (a post whose referenced reservation is missing, and so on) can be inserted
     * directly. PostgreSQL's schema CHECK constraints block them, so it is false, and the section where such rows
     * become
     * claim candidates (F29) is skipped.
     */
    default boolean allowsMalformedRows() {
        return true;
    }
}
