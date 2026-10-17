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
 * 앱 트랜잭션이 READ COMMITTED보다 높을 때의 동시 접수(P2b-17). 접수는 앱의 격리 수준을 따른다. 높은 수준에서는 기다림이
 * 직렬화 실패(SQLSTATE 40001)로 바뀌고 재시도는 앱이 한다. 어느 수준에서도 행이 둘 생기거나 순번이 꼬이지 않는다.
 */
@DisplayName("높은 격리 수준의 앱 트랜잭션에서 동시 접수 — P2b-17")
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
        relay = new CommandLifecycle(storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
    }

    @Nested
    @Order(1)
    @DisplayName("두 앱 트랜잭션이 같은 commandId를 겹쳐 접수하면")
    class SubmittingTheSameCommandTwice {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("늦은 쪽은 재시도할 수 있는 실패로 끝나고, 행은 먼저 커밋한 쪽 하나다")
        void aConcurrentDuplicateEndsInOneRowOrARetryableFailure(AppIsolation isolation) {
            // given: 첫 앱 트랜잭션이 pay-1을 접수하고 아직 커밋하지 않았다
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
    @DisplayName("두 앱 트랜잭션이 같은 순서 키에 다른 명령을 겹쳐 접수하면")
    class SubmittingOnOneKey {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("늦은 쪽은 재시도할 수 있는 실패로 끝나고, 재시도하면 다음 순번을 받는다 — 순번을 나눠 갖거나 뒤집지 않는다")
        void concurrentSubmitsOnOneKeyNeverShareOrInvertASequence(AppIsolation isolation) {
            // given: 첫 앱 트랜잭션이 order-1에 pay-1을 접수하고 아직 커밋하지 않았다
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(app,
                    () -> relay.submit(transfer("pay-1", "order-1")));
                 OtherTransaction<CommandReceipt> second = startAfter(first, app,
                         () -> relay.submit(transfer("pay-2", "order-1")))) {
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();
                assertEndedInARetryableFailure(second);

                // when: 앱이 새 트랜잭션에서 다시 접수한다
                app.inTransaction(() -> relay.submit(transfer("pay-2", "order-1")));

                // then
                assertRowsOfKey("order-1", "pay-1:1", "pay-2:2");
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("두 앱 트랜잭션이 같은 commandId를 다른 순서 키로 겹쳐 접수하면")
    class SubmittingTheSameCommandOnAnotherKey {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("순번 단계에서 막히지 않아 중복 검사(ON CONFLICT)까지 가고, 늦은 쪽은 재시도할 수 있는 실패로 끝나며 행은 하나다")
        void aDuplicateOnAnotherKeyEndsInOneRowOrARetryableFailure(AppIsolation isolation) {
            // given: 첫 앱 트랜잭션이 order-1에 pay-1을 접수하고 아직 커밋하지 않았다
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(app,
                    () -> relay.submit(transfer("pay-1", "order-1")));
                 OtherTransaction<CommandReceipt> second = startAfter(first, app,
                         () -> relay.submit(transfer("pay-1", "order-2")))) {

                // when: 둘째는 commandId 유일 인덱스에서 첫째의 커밋을 기다린다
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

    /** 늦은 접수가 일하는 중에 또는 커밋할 때 직렬화 실패로 끝났다. 앱은 이 SQLSTATE를 보고 재시도한다. */
    private static void assertEndedInARetryableFailure(OtherTransaction<?> late) {
        Throwable failure = catchThrowable(() -> {
            late.awaitWork();
            late.commit();
        });
        assertThat(sqlState(failure)).as("늦은 접수의 실패 SQLSTATE (실패: %s)", failure).isEqualTo(SERIALIZATION_FAILURE);
    }

    /** 이 순서 키의 행을 순번 순서로 "commandId:seq"로 본다. */
    private void assertRowsOfKey(String orderingKey, String... commandIdAndSeq) {
        assertThat(storage.sql().sql("SELECT command_id || ':' || seq FROM ledger_command WHERE ordering_key = :key ORDER BY seq")
                .param("key", orderingKey).query(String.class).list())
                .as("%s의 행(commandId:seq)", orderingKey).containsExactly(commandIdAndSeq);
    }

    /** 앱이 연 트랜잭션: 그 격리 수준의 Spring 트랜잭션. 접수는 여기에 합류한다. */
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
