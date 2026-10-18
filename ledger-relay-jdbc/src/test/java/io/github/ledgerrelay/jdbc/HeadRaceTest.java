package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 키에서 맨 앞의 완료와 뒤 명령의 접수가 겹칠 때(02e P2e-4). 둘 다 키 행을 고치므로 늦은 쪽이 기다리거나(READ
 * COMMITTED) 직렬화 실패로 끝난다(더 높은 격리 수준). 어느 쪽이든 맨 앞이 사라져 그 키가 영원히 안 나가는 일은 없다.
 */
@DisplayName("같은 키의 완료와 접수가 겹치면 — 02e P2e-4")
class HeadRaceTest {

    static final Duration BLOCKED = Duration.ofMillis(300);
    static final Duration LEASE = Duration.ofSeconds(30);
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
    ClaimedCommand pay1;

    @BeforeEach
    void givenPay1IsSubmittedToTheLedger() {
        storage = PostgresStorage.withDataSourceTransactions();
        relay = new CommandLifecycle(storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        storage.transactions().inTransaction(() -> relay.submit(transfer("pay-1")));
        pay1 = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(pay1);
    }

    @AfterEach
    void theHeadsStayConsistent() {
        storage.assertHeadsConsistent();
    }

    @Nested
    @Order(1)
    @DisplayName("READ COMMITTED에서")
    class ReadCommitted {

        @Test
        @DisplayName("완료가 열려 있으면 접수는 기다렸다가 맨 앞이 된다")
        void aSubmitWaitsForAnOpenCompletionAndBecomesTheHead() {
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(), this::complete);
                 OtherTransaction<CommandReceipt> submit = startAfter(completion, storage.transactions(),
                         () -> relay.submit(transfer("pay-2")))) {
                submit.assertStillWorkingAfter(BLOCKED);

                // when
                completion.commit();
                submit.awaitWork();
                submit.commit();

                // then
                assertHeadOf("order-1", "pay-2");
            }
        }

        @Test
        @DisplayName("접수가 열려 있으면 완료는 기다렸다가 맨 앞을 넘긴다")
        void aCompletionWaitsForAnOpenSubmitAndPassesTheHeadOn() {
            try (OtherTransaction<CommandReceipt> submit = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-2")));
                 OtherTransaction<Boolean> completion = startAfter(submit, storage.transactions(), this::complete)) {
                completion.assertStillWorkingAfter(BLOCKED);

                // when
                submit.commit();
                completion.awaitWork();
                completion.commit();

                // then
                assertHeadOf("order-1", "pay-2");
            }
        }

        private boolean complete() {
            return HeadRaceTest.this.complete();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("앱 트랜잭션이 더 높은 격리 수준이면")
    class HigherIsolation {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("완료 뒤에 기다린 접수는 재시도할 수 있는 실패로 끝나고, 다시 접수하면 맨 앞이 된다")
        void aRepeatableReadSubmitAfterACompletionEndsInARetryableFailure(AppIsolation isolation) {
            // given
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(),
                    HeadRaceTest.this::complete);
                 OtherTransaction<CommandReceipt> submit = startAfter(completion, app,
                         () -> relay.submit(transfer("pay-2")))) {
                submit.assertStillWorkingAfter(BLOCKED);
                completion.commit();
                assertEndedInARetryableFailure(submit);
                assertNoHeadOf("order-1");

                // when
                app.inTransaction(() -> relay.submit(transfer("pay-2")));

                // then
                assertHeadOf("order-1", "pay-2");
            }
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 워커가 pay-1의 TB 결과를 쓴다. 핸들러 없이 저장소의 조건부 쓰기만 한다. */
    private boolean complete() {
        long submitCount = storage.sql().sql("SELECT submit_count FROM ledger_command WHERE id = :id")
                .param("id", pay1.rowId()).query(Long.class).single();
        CommandRow applied = new CommandRow(StoredState.APPLIED, pay1.leaseToken(), submitCount, Optional.of(Created),
                Optional.of(Created), 0);
        boolean written = storage.store().compareAndSet(new RowChange(pay1.rowId(), StoredState.CLAIMED,
                pay1.leaseToken(), applied, new RowChange.Stamp.Completion()));
        if (!written) {
            throw new IllegalStateException("given 준비 실패: pay-1의 결과를 쓰지 못했다");
        }
        return true;
    }

    private void assertHeadOf(String orderingKey, String commandId) {
        assertThat(heads(orderingKey)).as("%s의 맨 앞", orderingKey).containsExactly(commandId);
    }

    private void assertNoHeadOf(String orderingKey) {
        assertThat(heads(orderingKey)).as("%s의 맨 앞", orderingKey).isEmpty();
    }

    private java.util.List<String> heads(String orderingKey) {
        return storage.sql().sql("SELECT command_id FROM ledger_command WHERE ordering_key = :key AND is_head")
                .param("key", orderingKey).query(String.class).list();
    }

    private static LedgerCommand transfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-1")
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

    private static <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, Transactions transactions,
            Supplier<T> work) {
        earlier.awaitWork();
        return OtherTransaction.start(transactions, work);
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
