package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("트랜잭션 어댑터 — 격리 수준(P2b-16, P2b-17)과 접수할 트랜잭션의 확인(F18)")
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

    @Nested
    @Order(2)
    @DisplayName("열린 트랜잭션에 저장소의 커넥션이 묶여 있지 않으면 — G1, F18")
    class WhenTheStoresConnectionIsNotInTheTransaction {

        @Test
        @DisplayName("JPA 트랜잭션이 JDBC 커넥션을 노출하지 않으면 접수는 거부되고, 명령은 따로 커밋되지 않는다")
        void aJpaTransactionHidingTheConnectionIsRefused() {
            // given
            PostgresStorage storage = PostgresStorage.withJpaTransactions(new PostgresStorage.NonExposingJpaDialect());
            CommandLifecycle relay = new CommandLifecycle(storage.store(), storage.transactions(),
                    new SequentialTransferIds(), new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));

            // when
            ThrowingCallable submitting = () -> storage.transactions().inTransaction(() -> relay.submit(
                    LedgerCommand.transfer("pay-1").orderingKey("order-1").debit(1).credit(2).amount(500).ledger(1).code(1)
                            .build()));

            // then
            assertRefusedAsNotJoined(submitting);
            assertThat(storage.sql().sql("SELECT count(*) FROM ledger_command").query(Long.class).single())
                    .as("따로 커밋된 명령").isZero();
        }

        @Test
        @DisplayName("트랜잭션 매니저가 다른 DataSource(프록시)를 쥐고 있으면 접수할 트랜잭션으로 인정하지 않는다")
        void aTransactionOnAnotherDataSourceIsRefused() {
            // given
            PostgresStorage storage = PostgresStorage.withDataSourceTransactions();
            DataSourceTransactionManager onAProxy = new DataSourceTransactionManager(
                    new DelegatingDataSource(storage.dataSource()));
            JdbcTransactions transactions = new JdbcTransactions(onAProxy, storage.dataSource());

            // when
            ThrowingCallable requiring = () -> transactions.inTransaction(() -> {
                transactions.requireActive();
                return null;
            });

            // then
            assertRefusedAsNotJoined(requiring);
        }
    }

    private static void assertRefusedAsNotJoined(ThrowingCallable submitting) {
        assertThatThrownBy(submitting).as("저장소의 커넥션이 묶이지 않은 트랜잭션에서의 접수")
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("DataSource");
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
