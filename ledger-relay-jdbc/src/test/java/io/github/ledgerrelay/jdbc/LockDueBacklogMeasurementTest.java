package io.github.ledgerrelay.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 측정용(02d Task 9, 전체 리뷰 R-jdbc-I1, 02e F32). 막힌 키 하나 뒤에 대기 명령이 쌓였을 때, 맨 앞이 backoff 중인 키가 많을
 * 때, 보낼 수 있는 맨 앞이 몰렸을 때 claim 질의의 비용을 잰다. 판정하지 않고 실행 계획만 남긴다. 판정은
 * {@link LockDueBoundedWorkTest}가 한다. 기본 실행에서 빠지며 {@code ./gradlew :ledger-relay-jdbc:measurement}로 돌린다.
 */
@Tag("measurement")
@DisplayName("측정: 막힌 키의 백로그와 claim 질의 비용 — R-jdbc-I1")
class LockDueBacklogMeasurementTest {

    static final int BACKLOG = 100_000;
    static final int OTHER_KEYS = 100;

    PostgresStorage storage;

    @BeforeEach
    void givenAnEmptyStore() {
        storage = PostgresStorage.withDataSourceTransactions();
    }

    @Test
    @DisplayName("맨 앞만 있는 막힌 키 + 다른 키 100개 (기준)")
    void withoutBacklog() {
        blockedHead();
        otherDueKeys();
        report("기준: 막힌 키에 맨 앞 하나");
    }

    @Test
    @DisplayName("맨 앞 뒤에 대기 10만 건이 있는 막힌 키 + 다른 키 100개")
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
        report("백로그 10만 건");
    }

    @Test
    @DisplayName("맨 앞이 backoff 중인 키 10만 개 + 다른 키 100개")
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
        report("backoff 중인 맨 앞 10만 개");
    }

    @Test
    @DisplayName("보낼 수 있는 맨 앞 10만 개 — TB 회복 직후")
    void withManyReadyHeads() {
        dueKeys(BACKLOG);
        report("보낼 수 있는 맨 앞 10만 개");
    }

    /** 키 hot의 맨 앞(seq 1)은 backoff 중이라 아직 후보가 아니다. 그래서 그 키는 막혀 있다. */
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

    /** 보낼 수 있는 키 n개를 넣고, 직접 넣은 행의 맨 앞 표시를 정한 뒤(02e P2e-5) 통계를 갱신한다. */
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
            System.out.println("=== " + label + " (실행 " + run + ") ===\n" + plan);
        }
    }
}
