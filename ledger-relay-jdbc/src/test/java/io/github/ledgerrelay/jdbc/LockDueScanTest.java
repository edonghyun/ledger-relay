package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** 워커는 짧은 간격으로 계속 claim한다. 한 번의 claim이 끝난 이력의 크기에 비례해 일하면 안 된다(F19). */
@DisplayName("claim 후보 질의의 비용 — 끝난 이력이 쌓여도 미완료 행만 읽는다")
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
    @DisplayName("끝난 명령 20만 건 뒤에 접수된 명령 100건이 있으면")
    class BehindALongHistory {

        @Test
        @DisplayName("claim 질의는 끝난 행을 하나씩 걸러 내며 훑지 않는다")
        void theDueQueryDoesNotFilterThroughFinishedRows() {
            // when
            String plan = storage.transactions().inTransaction(() -> storage.sql()
                    .sql("EXPLAIN (ANALYZE, FORMAT TEXT) " + JdbcCommandStore.LOCK_DUE).param("limit", 10)
                    .query(String.class).list().stream().reduce("", (a, b) -> a + b + "\n"));

            // then
            assertFilteredOutAtMost(100, plan);
        }
    }

    /** 실행 계획의 "Rows Removed by Filter"를 모두 더한 값이 상한 이하다. */
    private static void assertFilteredOutAtMost(long limit, String plan) {
        Matcher removed = Pattern.compile("Rows Removed by Filter: (\\d+)").matcher(plan);
        long total = 0;
        while (removed.find()) {
            total += Long.parseLong(removed.group(1));
        }
        assertThat(total).as("걸러 낸 행 수 (실행 계획:%n%s)", plan).isLessThanOrEqualTo(limit);
    }
}
