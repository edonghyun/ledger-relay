package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** Workers claim continuously at short intervals. One claim must not do work proportional to the size of finished history (F19). */
@DisplayName("Cost of the claim candidate query — it reads only unfinished rows as finished history grows")
class LockDueScanTest {

    static final int FINISHED_HISTORY = 200_000;
    static final int PENDING = 100;

    PostgresStorage storage;

    @BeforeEach
    void givenALongFinishedHistoryAndAFewPendingCommands() {
        storage = PostgresStorage.withDataSourceTransactions();
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state, completed_at)
                SELECT 'done-' || g, g, 'key-' || g, 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'APPLIED', now()
                FROM generate_series(1, :n) g
                """).param("n", FINISHED_HISTORY).update();
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state)
                SELECT 'pay-' || g, 1000000 + g, 'order-' || g, 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'PENDING'
                FROM generate_series(1, :n) g
                """).param("n", PENDING).update();
        storage.rebuildHeads();
        storage.sql().sql("ANALYZE ledger_command").update();
    }

    @Nested
    @Order(1)
    @DisplayName("When 100 accepted commands sit behind 200,000 finished commands")
    class BehindALongHistory {

        @Test
        @DisplayName("The claim query does not scan through finished rows filtering them out one by one")
        void theDueQueryDoesNotFilterThroughFinishedRows() {
            // when
            String plan = storage.transactions().inTransaction(() -> storage.sql()
                    .sql("EXPLAIN (ANALYZE, FORMAT TEXT) " + JdbcCommandStore.LOCK_DUE).param("limit", 10)
                    .query(String.class).list().stream().reduce("", (a, b) -> a + b + "\n"));

            // then
            assertFilteredOutAtMost(100, plan);
        }
    }

    /** The sum of all "Rows Removed by Filter" in the query plan is within the limit. */
    private static void assertFilteredOutAtMost(long limit, String plan) {
        Matcher removed = Pattern.compile("Rows Removed by Filter: (\\d+)").matcher(plan);
        long total = 0;
        while (removed.find()) {
            total += Long.parseLong(removed.group(1));
        }
        assertThat(total).as("rows filtered out (query plan:%n%s)", plan).isLessThanOrEqualTo(limit);
    }
}
