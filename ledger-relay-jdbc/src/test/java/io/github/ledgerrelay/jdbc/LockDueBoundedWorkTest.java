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
 * 한 번의 claim이 읽는 페이지는 "지금 보낼 수 있는 맨 앞" limit개에만 비례한다(02e, F32). 막힌 키 뒤의 백로그, 맨 앞이
 * backoff 중인 키의 수, 보낼 수 있는 맨 앞의 수 어느 것에도 늘지 않는다. limit 128건을 잠그는 데 약 260페이지를 읽는다.
 * 옛 질의는 백로그 한 건에 약 4페이지를 읽었다.
 */
@DisplayName("claim 질의가 읽는 양 — 보낼 수 있는 맨 앞 limit개에만 비례한다 (02e, F32)")
class LockDueBoundedWorkTest {

    static final int LIMIT = 128;
    static final long PAGE_BUDGET = 1_000;
    static final int MANY = 20_000;
    /**
     * 회복 몰림 장면의 끝난 이력. 접수 순서로 고르면 플래너가 기본 키 순서로 이력을 걸러 내며 읽어 이 수에 비례한다.
     * 2만이면 약 1,030페이지로 상한에 겨우 걸리므로 크게 둔다.
     */
    static final int HISTORY = 50_000;
    static final int READY_KEYS = 100;
    /** 키 하나의 맨 앞을 넘기는 문장의 상한: 다음 미완료를 인덱스에서 하나 찾고 그 행을 고친다. */
    static final long ONE_KEY_BUDGET = 50;
    /**
     * health 한 번이 대기를 세며 읽을 페이지의 상한. 세는 질의는 대기 인덱스에서 상한(1,000건)까지 읽고, visibility map이
     * 없으면 행마다 힙을 확인해 약 2,000페이지를 읽는다. 대기 크기와는 무관하다.
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
    @DisplayName("보낼 수 없는 명령이 많아도")
    class ManyCommandsThatCannotGoYet {

        @Test
        @DisplayName("막힌 키 뒤의 대기 2만 건은 읽지 않는다")
        void aBacklogBehindABlockedKeyIsNotRead() {
            // given: 키 hot의 맨 앞은 1시간 backoff 중이고, 그 뒤에 2만 건이 기다린다
            inserted("'hot-' || g", "'hot'", "g", "now() + CASE WHEN g = 1 THEN interval '1 hour' ELSE interval '0' END",
                    1, MANY + 1);
            readyKeys(READY_KEYS);
            prepared();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan());
        }

        @Test
        @DisplayName("맨 앞이 backoff 중인 키 2만 개는 읽지 않는다")
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
    @DisplayName("보낼 수 있는 맨 앞이 많으면")
    class ManyReadyHeads {

        @Test
        @DisplayName("limit개를 잠그면 멈춘다 — 끝난 이력을 접수 순서로 훑거나 전부 정렬하지 않는다")
        void manyReadyHeadsStopAtTheLimit() {
            // given: TB가 회복된 직후처럼 보낼 수 있는 맨 앞 2만 개가 끝난 이력 5만 건 뒤에 있다
            finishedHistory(HISTORY);
            readyKeys(MANY);
            prepared();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan());
        }
    }

    @Nested
    @Order(3)
    @DisplayName("끝난 맨 앞이 많아도")
    class ManyFinishedHeads {

        @Test
        @DisplayName("넘기기는 limit개 키를 잠그면 멈춘다 — 끝난 이력을 훑지 않는다 (P2e-7)")
        void passingOnStopsAtTheLimit() {
            // given: TB가 회복된 직후처럼 방금 끝난 맨 앞 2만 개가 넘길 차례를 기다리고, 그 앞에 끝난 이력 5만 건이 있다
            finishedHistory(HISTORY);
            finishedHeads(MANY);
            storage.sql().sql("ANALYZE ledger_command").update();
            storage.sql().sql("ANALYZE ledger_ordering_key").update();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan(JdbcOrderingKeys.LOCK_KEYS_TO_PASS_ON));
        }

        @Test
        @DisplayName("막힌 키의 맨 앞이 끝나 넘길 때 그 뒤 대기 2만 건은 읽지 않는다 — 다음 미완료 하나만 찾는다 (P2e-7)")
        void passingOnABlockedKeyDoesNotReadItsBacklog() {
            // given: 키 hot의 맨 앞 hot-1이 방금 끝났고, 그 뒤에 2만 건이 기다린다
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
    @DisplayName("대기를 세면 — 03b health")
    class CountingTheBacklog {

        @Test
        @DisplayName("TB 장애로 대기가 10만 건 쌓여도 health 한 번이 읽는 양은 묶여 있다 — 장애 중에 health가 느려지지 않게")
        void countingAHugeBacklogReadsABoundedNumberOfPages() {
            // given: 끝난 이력 5만 건 뒤에, 키 10만 개마다 대기 명령이 하나씩 있다(TB가 멈춘 동안 쌓인 접수)
            finishedHistory(HISTORY);
            inserted("'wait-' || g", "'wait-' || g", "1", "now()", 1, OUTAGE_BACKLOG);
            prepared();

            // then
            long pages = JdbcCommandStore.BACKLOG.stream().mapToLong(query -> pagesRead(plan(query, "cap",
                    JdbcCommandStore.BACKLOG_COUNT_CAP))).sum();
            assertThat(pages).as("대기를 세는 데 읽은 페이지").isLessThanOrEqualTo(BACKLOG_BUDGET);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 지금 보낼 수 있는 키 n개. 키마다 접수 명령 하나다. */
    private void readyKeys(int n) {
        inserted("'pay-' || g", "'order-' || g", "1", "now()", 1, n);
    }

    /** 끝난 명령 n건. 키마다 하나다. */
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

    /** 끝났는데 아직 맨 앞인 명령 n건과 그 키 행. 키마다 하나다. */
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

    /** 접수 상태의 행을 g = from..to로 직접 넣는다. 이체 ID는 이미 있는 가장 큰 값 뒤로 잇는다. */
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

    /** 직접 넣은 행의 맨 앞 표시를 정하고(P2e-5) 통계를 갱신한다. */
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

    /** 실행 계획 맨 위 노드의 공유 버퍼 페이지(hit + read)가 상한 이하다. 맨 위 노드의 수는 그 아래 모두를 더한 값이다. */
    private static void assertReadAtMostPages(long budget, String plan) {
        assertThat(pagesRead(plan)).as("claim 한 번이 읽은 페이지 (실행 계획:%n%s)", plan).isLessThanOrEqualTo(budget);
    }

    private static long pagesRead(String plan) {
        Matcher top = Pattern.compile("Buffers: shared(?: hit=(\\d+))?(?: read=(\\d+))?").matcher(plan);
        assertThat(top.find()).as("실행 계획에 버퍼 수가 있다:%n%s", plan).isTrue();
        return (top.group(1) == null ? 0 : Long.parseLong(top.group(1)))
                + (top.group(2) == null ? 0 : Long.parseLong(top.group(2)));
    }
}
