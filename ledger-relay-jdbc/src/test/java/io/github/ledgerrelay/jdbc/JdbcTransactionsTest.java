package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("트랜잭션 어댑터의 격리 수준 — P2b-16, P2b-17")
class JdbcTransactionsTest {

    static final String TRANSACTION_REPEATABLE_READ = "TRANSACTION_REPEATABLE_READ";

    @Nested
    @Order(1)
    @DisplayName("커넥션 풀의 기본 격리 수준이 REPEATABLE READ이면")
    class WhenThePoolDefaultsToRepeatableRead {

        @Test
        @DisplayName("어댑터가 새로 여는 트랜잭션은 READ COMMITTED다 — 워커는 기다리지 직렬화 실패로 터지지 않는다")
        void aNewTransactionRunsAtReadCommitted() {
            // given
            PostgresStorage storage = PostgresStorage.withDataSourceTransactions(TRANSACTION_REPEATABLE_READ);

            // when
            String level = storage.transactions().inTransaction(() -> currentIsolation(storage));

            // then
            assertThat(level).as("새 트랜잭션의 격리 수준").isEqualTo("read committed");
        }

        @Test
        @DisplayName("앱이 연 SERIALIZABLE 트랜잭션에 합류하면 앱의 수준을 바꾸지 않는다")
        void joiningKeepsTheAppsLevel() {
            // given
            PostgresStorage storage = PostgresStorage.withDataSourceTransactions(TRANSACTION_REPEATABLE_READ);
            TransactionTemplate app = appTransaction(storage, ISOLATION_SERIALIZABLE);

            // when
            String level = app.execute(status -> storage.transactions().inTransaction(() -> currentIsolation(storage)));

            // then
            assertThat(level).as("합류한 트랜잭션의 격리 수준").isEqualTo("serializable");
        }
    }

    private static String currentIsolation(PostgresStorage storage) {
        return storage.sql().sql("SHOW transaction_isolation").query(String.class).single();
    }

    private static TransactionTemplate appTransaction(PostgresStorage storage, int isolation) {
        TransactionTemplate app = new TransactionTemplate(storage.transactionManager());
        app.setIsolationLevel(isolation);
        return app;
    }
}
