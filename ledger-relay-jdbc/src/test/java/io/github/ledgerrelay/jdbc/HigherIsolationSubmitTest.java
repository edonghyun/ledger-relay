package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrent acceptance when the app transaction runs above READ COMMITTED. Acceptance follows the app's isolation
 * level. At higher levels, waiting turns into a serialization failure (SQLSTATE 40001) and the app does the retry. At no
 * level does a second row appear or the sequence numbers get mixed up.
 */
@DisplayName("Concurrent submits in app transactions at a high isolation level")
class HigherIsolationSubmitTest {

    static final Duration BLOCKED = Duration.ofMillis(300);
    static final String SERIALIZATION_FAILURE = "40001";

    enum AppIsolation {
        REPEATABLE_READ(TransactionDefinition.ISOLATION_REPEATABLE_READ),
        SERIALIZABLE(TransactionDefinition.ISOLATION_SERIALIZABLE);

        final int level;

        AppIsolation(int level) {
            this.level = level;
        }
    }

    PostgresStorage storage;
    CommandLifecycle relay;

    @BeforeEach
    void givenAnEmptyRelay() {
        storage = PostgresStorage.withDataSourceTransactions();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
    }

    @Nested
    @Order(1)
    @DisplayName("When two app transactions submit the same commandId at the same time")
    class SubmittingTheSameCommandTwice {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("The later one ends in a retryable failure, and the only row is the one that committed first")
        void aConcurrentDuplicateEndsInOneRowOrARetryableFailure(AppIsolation isolation) {
            // given: the first app transaction has submitted pay-1 and not committed yet
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(app,
                    () -> relay.submit(transfer("pay-1", "order-1")));
                 OtherTransaction<CommandReceipt> second = startAfter(first, app,
                         () -> relay.submit(transfer("pay-1", "order-1")))) {

                // when
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();

                // then
                assertEndedInARetryableFailure(second);
                assertRowsOfKey("order-1", "pay-1:1");
            }
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When two app transactions submit different commands on the same ordering key at the same time")
    class SubmittingOnOneKey {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("The later one ends in a retryable failure and gets the next sequence number on retry — sequence numbers are never shared or inverted")
        void concurrentSubmitsOnOneKeyNeverShareOrInvertASequence(AppIsolation isolation) {
            // given: the first app transaction has submitted pay-1 on order-1 and not committed yet
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(app,
                    () -> relay.submit(transfer("pay-1", "order-1")));
                 OtherTransaction<CommandReceipt> second = startAfter(first, app,
                         () -> relay.submit(transfer("pay-2", "order-1")))) {
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();
                assertEndedInARetryableFailure(second);

                // when: the app submits again in a new transaction
                app.inTransaction(() -> relay.submit(transfer("pay-2", "order-1")));

                // then
                assertRowsOfKey("order-1", "pay-1:1", "pay-2:2");
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When two app transactions submit the same commandId on different ordering keys at the same time")
    class SubmittingTheSameCommandOnAnotherKey {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("Nothing blocks at the sequence step, so both reach the duplicate check (ON CONFLICT); the later one ends in a retryable failure and there is one row")
        void aDuplicateOnAnotherKeyEndsInOneRowOrARetryableFailure(AppIsolation isolation) {
            // given: the first app transaction has submitted pay-1 on order-1 and not committed yet
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(app,
                    () -> relay.submit(transfer("pay-1", "order-1")));
                 OtherTransaction<CommandReceipt> second = startAfter(first, app,
                         () -> relay.submit(transfer("pay-1", "order-2")))) {

                // when: the second waits on the commandId unique index for the first to commit
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();

                // then
                assertEndedInARetryableFailure(second);
                assertRowsOfKey("order-1", "pay-1:1");
                assertRowsOfKey("order-2");
            }
        }
    }

    private static LedgerCommand transfer(String commandId, String orderingKey) {
        return LedgerCommand.transfer(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }

    /** The later submit ended in a serialization failure, during its work or at commit. The app retries on this SQLSTATE. */
    private static void assertEndedInARetryableFailure(OtherTransaction<?> late) {
        Throwable failure = catchThrowable(() -> {
            late.awaitWork();
            late.commit();
        });
        assertThat(sqlState(failure)).as("failure SQLSTATE of the later submit (failure: %s)", failure).isEqualTo(SERIALIZATION_FAILURE);
    }

    /** Checks the rows of this ordering key in sequence order, as "commandId:seq". */
    private void assertRowsOfKey(String orderingKey, String... commandIdAndSeq) {
        assertThat(storage.sql().sql("SELECT command_id || ':' || seq FROM ledger_command WHERE ordering_key = :key ORDER BY seq")
                .param("key", orderingKey).query(String.class).list())
                .as("rows of %s (commandId:seq)", orderingKey).containsExactly(commandIdAndSeq);
    }

    /** A transaction opened by the app: a Spring transaction at that isolation level. The submit joins it. */
    private Transactions appTransactions(AppIsolation isolation) {
        TransactionTemplate template = new TransactionTemplate(storage.transactionManager());
        template.setIsolationLevel(isolation.level);
        return new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return template.execute(status -> work.get());
            }

            @Override
            public void requireActive() {
                storage.transactions().requireActive();
            }

            @Override
            public boolean isActive() {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }
        };
    }

    private static <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, Transactions app, Supplier<T> work) {
        earlier.awaitWork();
        return OtherTransaction.start(app, work);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
