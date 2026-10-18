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
    static final int READY_KEYS = 100;

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
        @DisplayName("limit개를 잠그면 멈춘다 — 전부 정렬하지 않는다")
        void manyReadyHeadsStopAtTheLimit() {
            // given
            readyKeys(MANY);
            prepared();

            // then
            assertReadAtMostPages(PAGE_BUDGET, plan());
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 지금 보낼 수 있는 키 n개. 키마다 접수 명령 하나다. */
    private void readyKeys(int n) {
        inserted("'pay-' || g", "'order-' || g", "1", "now()", 1, n);
    }

    /** 접수 상태의 행을 g = from..to로 직접 넣는다. 이체 ID가 겹치지 않게 이름으로 띄운다. */
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
        return storage.transactions().inTransaction(() -> storage.sql()
                .sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + JdbcCommandStore.LOCK_DUE).param("limit", LIMIT)
                .query(String.class).list().stream().reduce("", (a, b) -> a + b + "\n"));
    }

    /** 실행 계획 맨 위 노드의 공유 버퍼 페이지(hit + read)가 상한 이하다. 맨 위 노드의 수는 그 아래 모두를 더한 값이다. */
    private static void assertReadAtMostPages(long budget, String plan) {
        Matcher top = Pattern.compile("Buffers: shared(?: hit=(\\d+))?(?: read=(\\d+))?").matcher(plan);
        assertThat(top.find()).as("실행 계획에 버퍼 수가 있다:%n%s", plan).isTrue();
        long pages = (top.group(1) == null ? 0 : Long.parseLong(top.group(1)))
                + (top.group(2) == null ? 0 : Long.parseLong(top.group(2)));
        assertThat(pages).as("claim 한 번이 읽은 페이지 (실행 계획:%n%s)", plan).isLessThanOrEqualTo(budget);
    }
}
