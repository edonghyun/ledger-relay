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

@DisplayName("Transaction adapter — isolation level and the check of the transaction to accept in (F18)")
class JdbcTransactionsTest {

    static final String TRANSACTION_REPEATABLE_READ = "TRANSACTION_REPEATABLE_READ";

    @Nested
    @Order(1)
    @DisplayName("When the connection pool defaults to REPEATABLE READ")
    class WhenThePoolDefaultsToRepeatableRead {

        @Test
        @DisplayName("A transaction the adapter opens runs at READ COMMITTED — workers wait instead of failing with a serialization failure")
        void aNewTransactionRunsAtReadCommitted() {
            // given
            PostgresStorage storage = PostgresStorage.withDataSourceTransactions(TRANSACTION_REPEATABLE_READ);

            // when
            String level = storage.transactions().inTransaction(() -> currentIsolation(storage));

            // then
            assertThat(level).as("isolation level of the new transaction").isEqualTo("read committed");
        }

        @Test
        @DisplayName("Joining a SERIALIZABLE transaction opened by the app keeps the app's level")
        void joiningKeepsTheAppsLevel() {
            // given
            PostgresStorage storage = PostgresStorage.withDataSourceTransactions(TRANSACTION_REPEATABLE_READ);
            TransactionTemplate app = appTransaction(storage, ISOLATION_SERIALIZABLE);

            // when
            String level = app.execute(status -> storage.transactions().inTransaction(() -> currentIsolation(storage)));

            // then
            assertThat(level).as("isolation level of the joined transaction").isEqualTo("serializable");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When the store's connection is not bound to the open transaction — G1, F18")
    class WhenTheStoresConnectionIsNotInTheTransaction {

        @Test
        @DisplayName("If the JPA transaction does not expose the JDBC connection, the submit is rejected and no command commits separately")
        void aJpaTransactionHidingTheConnectionIsRefused() {
            // given
            PostgresStorage storage = PostgresStorage.withJpaTransactions(new PostgresStorage.NonExposingJpaDialect());
            CommandLifecycle relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                    new SequentialTransferIds(), new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));

            // when
            ThrowingCallable submitting = () -> storage.transactions().inTransaction(() -> relay.submit(
                    LedgerCommand.transfer("pay-1").orderingKey("order-1").debit(1).credit(2).amount(500).ledger(1).code(1)
                            .build()));

            // then
            assertRefusedAsNotJoined(submitting);
            assertThat(storage.sql().sql("SELECT count(*) FROM ledger_command").query(Long.class).single())
                    .as("separately committed commands").isZero();
        }

        @Test
        @DisplayName("If the transaction manager holds a different DataSource (a proxy), the transaction is not accepted for submits")
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
        assertThatThrownBy(submitting).as("submit in a transaction without the store's connection bound")
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
