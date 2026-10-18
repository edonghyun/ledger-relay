package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * The pages one claim reads are proportional only to the limit of "heads sendable now" (F32). They do not grow with
 * the backlog behind a blocked key, with the number of keys whose head is in backoff, or with the number of sendable
 * heads. Locking a limit of 128 reads about 260 pages. The old query read about 4 pages per backlog row.
 */
@DisplayName("How much the claim query reads — proportional only to the limit of sendable heads (F32)")
class LockDueBoundedWorkTest {

    static final int LIMIT = 128;
    static final long PAGE_BUDGET = 1_000;
    static final int MANY = 20_000;
    /**
     * Finished history in the recovery-surge scenario. If candidates are picked in acceptance order, the planner reads
     * in primary key order filtering out history, so the cost is proportional to this number. With 20,000 it reads
     * about 1,030 pages and barely hits the budget, so the number is set high.
     */
    static final int HISTORY = 50_000;
    static final int READY_KEYS = 100;
    /** Budget for the statement that passes on the head of one key: find the next unfinished command in the index and update its row. */
    static final long ONE_KEY_BUDGET = 50;
    /**
     * Page budget for one health call counting the backlog. The counting queries read the waiting index up to the cap
     * (1,000 rows), and without a visibility map they check the heap for each row, reading about 2,000 pages. This
     * does not depend on the backlog size.
     */
    static final long BACKLOG_BUDGET = 2_500;
    static final int OUTAGE_BACKLOG = 100_000;

    PostgresStorage storage;

    @BeforeEach
    void givenAnEmptyStore() {
        storage = PostgresStorage.withDataSourceTransactions();
    }

    @Nested
    @Order(1)
    @DisplayName("Even with many commands that cannot go yet")
    class ManyCommandsThatCannotGoYet {

        @Test
        @DisplayName("20,000 waiting commands behind a blocked key are not read")
        void aBacklogBehindABlockedKeyIsNotRead() {
            // given: the head of key hot is in a 1-hour backoff, and 20,000 commands wait behind it
            inserted("'hot-' || g", "'hot'", "g", "now() + CASE WHEN g = 1 THEN interval '1 hour' ELSE interval '0' END",
                    1, MANY + 1);
            readyKeys(READY_KEYS);
            prepared();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan());
        }

        @Test
        @DisplayName("20,000 keys whose head is in backoff are not read")
        void headsWaitingForTheirRetryAreNotRead() {
            // given
            inserted("'wait-' || g", "'wait-' || g", "1", "now() + interval '1 hour'", 1, MANY);
            readyKeys(READY_KEYS);
            prepared();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan());
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When there are many sendable heads")
    class ManyReadyHeads {

        @Test
        @DisplayName("It stops after locking limit rows — it neither scans finished history in acceptance order nor sorts everything")
        void manyReadyHeadsStopAtTheLimit() {
            // given: as right after TB recovers, 20,000 sendable heads sit behind 50,000 rows of finished history
            finishedHistory(HISTORY);
            readyKeys(MANY);
            prepared();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan());
        }
    }

    @Nested
    @Order(3)
    @DisplayName("Even with many finished heads")
    class ManyFinishedHeads {

        @Test
        @DisplayName("Passing on stops after locking limit keys — it does not scan finished history")
        void passingOnStopsAtTheLimit() {
            // given: as right after TB recovers, 20,000 just-finished heads wait to be passed on, with 50,000 rows of finished history before them
            finishedHistory(HISTORY);
            finishedHeads(MANY);
            storage.sql().sql("ANALYZE ledger_command").update();
            storage.sql().sql("ANALYZE ledger_ordering_key").update();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan(JdbcOrderingKeys.LOCK_KEYS_TO_PASS_ON));
        }

        @Test
        @DisplayName("Passing on a blocked key whose head finished does not read the 20,000 waiting behind it — it finds only the next unfinished one")
        void passingOnABlockedKeyDoesNotReadItsBacklog() {
            // given: hot-1, the head of key hot, has just finished, and 20,000 commands wait behind it
            inserted("'hot-' || g", "'hot'", "g", "now()", 1, MANY + 1);
            prepared();
            storage.sql().sql("UPDATE ledger_command SET state = 'APPLIED', completed_at = now() WHERE command_id = 'hot-1'")
                    .update();
            storage.transactions().inTransaction(() -> storage.sql().sql(JdbcOrderingKeys.CLEAR_FINISHED_HEADS)
                    .param("keys", java.util.List.of("hot")).update());

            // then
            assertReadAtMostPages(ONE_KEY_BUDGET, plan(JdbcOrderingKeys.MARK_NEXT_HEADS, "keys", java.util.List.of("hot")));
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When counting the backlog — health")
    class CountingTheBacklog {

        @Test
        @DisplayName("Even with 100,000 waiting commands from a TB outage, one health call reads a bounded amount — so health does not slow down during the outage")
        void countingAHugeBacklogReadsABoundedNumberOfPages() {
            // given: behind 50,000 rows of finished history, each of 100,000 keys has one waiting command (acceptances that piled up while TB was down)
            finishedHistory(HISTORY);
            inserted("'wait-' || g", "'wait-' || g", "1", "now()", 1, OUTAGE_BACKLOG);
            prepared();

            // then
            long pages = JdbcCommandStore.BACKLOG.stream().mapToLong(query -> pagesRead(plan(query, "cap",
                    JdbcCommandStore.BACKLOG_COUNT_CAP))).sum();
            assertThat(pages).as("pages read to count the backlog").isLessThanOrEqualTo(BACKLOG_BUDGET);
        }
    }

    // ── Vocabulary used only in this file ──────────────────────────────

    /** n keys sendable now. One accepted command per key. */
    private void readyKeys(int n) {
        inserted("'pay-' || g", "'order-' || g", "1", "now()", 1, n);
    }

    /** n finished commands. One per key. */
    private void finishedHistory(int n) {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state, completed_at)
                SELECT 'done-' || g, 9000000 + g, 'done-' || g, 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash',
                    'APPLIED', now()
                FROM generate_series(1, :n) g
                """).param("n", n).update();
    }

    /** n commands that are finished but still the head, and their key rows. One per key. */
    private void finishedHeads(int n) {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state, completed_at, is_head)
                SELECT 'fin-' || g, 8000000 + g, 'fin-' || g, 1, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash',
                    'APPLIED', now(), true
                FROM generate_series(1, :n) g
                """).param("n", n).update();
        storage.sql().sql("INSERT INTO ledger_ordering_key (ordering_key, next_seq) SELECT 'fin-' || g, 2 "
                + "FROM generate_series(1, :n) g").param("n", n).update();
    }

    /** Inserts accepted rows directly for g = from..to. Transfer ids continue after the largest existing one. */
    private void inserted(String commandId, String orderingKey, String seq, String nextAttemptAt, int from, int to) {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state, next_attempt_at)
                SELECT %s, (SELECT coalesce(max(transfer_id), 0) FROM ledger_command) + g, %s, %s, 'TRANSFER', 1, 2,
                    500, 1, 1, 0, 0, 0, 'hash', 'PENDING', %s
                FROM generate_series(:from, :to) g
                """.formatted(commandId, orderingKey, seq, nextAttemptAt)).param("from", from).param("to", to).update();
    }

    /** Sets the head marks of the directly inserted rows and updates statistics. */
    private void prepared() {
        storage.rebuildHeads();
        storage.sql().sql("ANALYZE ledger_command").update();
    }

    private String plan() {
        return plan(JdbcCommandStore.LOCK_DUE);
    }

    private String plan(String query) {
        return plan(query, "limit", LIMIT);
    }

    private String plan(String query, String name, Object value) {
        return storage.transactions().inTransaction(() -> storage.sql()
                .sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + query).param(name, value)
                .query(String.class).list().stream().reduce("", (a, b) -> a + b + "\n"));
    }

    /** The shared buffer pages (hit + read) of the plan's top node are within the budget. The top node's count includes everything below it. */
    private static void assertReadAtMostPages(long budget, String plan) {
        assertThat(pagesRead(plan)).as("pages read by one claim (query plan:%n%s)", plan).isLessThanOrEqualTo(budget);
    }

    private static long pagesRead(String plan) {
        Matcher top = Pattern.compile("Buffers: shared(?: hit=(\\d+))?(?: read=(\\d+))?").matcher(plan);
        assertThat(top.find()).as("query plan has buffer counts:%n%s", plan).isTrue();
        return (top.group(1) == null ? 0 : Long.parseLong(top.group(1)))
                + (top.group(2) == null ? 0 : Long.parseLong(top.group(2)));
    }
}
