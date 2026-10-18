package io.github.ledgerrelay.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measurement (F32). Measures the cost of the claim query when waiting commands pile up behind one blocked key, when
 * many keys have a head in backoff, and when many sendable heads arrive at once. It asserts nothing and only records
 * query plans. {@link LockDueBoundedWorkTest} does the asserting. It is excluded from the default run; run it with
 * {@code ./gradlew :ledger-relay-jdbc:measurement}.
 */
@Tag("measurement")
@DisplayName("Measurement: backlog of a blocked key and the cost of the claim query")
class LockDueBacklogMeasurementTest {

    static final int BACKLOG = 100_000;
    static final int OTHER_KEYS = 100;

    PostgresStorage storage;

    @BeforeEach
    void givenAnEmptyStore() {
        storage = PostgresStorage.withDataSourceTransactions();
    }

    @Test
    @DisplayName("A blocked key with only its head + 100 other keys (baseline)")
    void withoutBacklog() {
        blockedHead();
        otherDueKeys();
        report("baseline: one head on the blocked key");
    }

    @Test
    @DisplayName("A blocked key with 100,000 waiting commands behind its head + 100 other keys")
    void withBacklog() {
        blockedHead();
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state)
                SELECT 'hot-' || g, 2000000 + g, 'hot', g, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'PENDING'
                FROM generate_series(2, :n + 1) g
                """).param("n", BACKLOG).update();
        otherDueKeys();
        report("backlog of 100,000");
    }

    @Test
    @DisplayName("100,000 keys whose head is in backoff + 100 other keys")
    void withManyHeadsWaitingForTheirRetry() {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state, next_attempt_at)
                SELECT 'wait-' || g, 2000000 + g, 'wait-' || g, 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash',
                    'PENDING', now() + interval '1 hour'
                FROM generate_series(1, :n) g
                """).param("n", BACKLOG).update();
        otherDueKeys();
        report("100,000 heads in backoff");
    }

    @Test
    @DisplayName("100,000 sendable heads — right after TB recovers")
    void withManyReadyHeads() {
        dueKeys(BACKLOG);
        report("100,000 sendable heads");
    }

    /** The head of key hot (seq 1) is in backoff and not a candidate yet. So that key is blocked. */
    private void blockedHead() {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state, next_attempt_at)
                VALUES ('hot-1', 1999999, 'hot', 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'PENDING',
                    now() + interval '1 hour')
                """).update();
    }

    private void otherDueKeys() {
        dueKeys(OTHER_KEYS);
    }

    /** Inserts n sendable keys, sets the head marks of the directly inserted rows, then updates statistics. */
    private void dueKeys(int n) {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state)
                SELECT 'pay-' || g, 3000000 + g, 'order-' || g, 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'PENDING'
                FROM generate_series(1, :n) g
                """).param("n", n).update();
        storage.rebuildHeads();
        storage.sql().sql("ANALYZE ledger_command").update();
    }

    private void report(String label) {
        for (int run = 1; run <= 3; run++) {
            String plan = storage.transactions().inTransaction(() -> storage.sql()
                    .sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + JdbcCommandStore.LOCK_DUE).param("limit", 128)
                    .query(String.class).list().stream().reduce("", (a, b) -> a + b + "\n"));
            System.out.println("=== " + label + " (run " + run + ") ===\n" + plan);
        }
    }
}
