package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.WriteResult;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * 같은 키에서 맨 앞의 완료와 뒤 명령의 접수가 겹칠 때. 완료는 순서 키 행을 잡지 않으므로 둘은 서로 기다리지
 * 않는다. 끝난 맨 앞은 다음 claim이 키 행을 잡아 넘기며, 앱이 그 키 행을 쥐고 있으면 그 키만 미룬다. 어느 격리 수준에서도
 * 맨 앞이 사라져 그 키가 영원히 안 나가는 일은 없다.
 */
@DisplayName("같은 키의 완료와 접수가 겹치면")
class HeadRaceTest {

    static final Duration BLOCKED = Duration.ofMillis(300);
    static final Duration NOT_BLOCKED = Duration.ofSeconds(2);
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
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
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
        @DisplayName("완료가 열려 있어도 접수는 기다리지 않고, 둘 다 커밋하면 다음 claim이 뒤 명령을 잡는다")
        void aSubmitDoesNotWaitForAnOpenCompletion() {
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(),
                    HeadRaceTest.this::complete);
                 OtherTransaction<CommandReceipt> submit = startAfter(completion, storage.transactions(),
                         () -> relay.submit(transfer("pay-2")))) {
                submit.assertFinishedWithin(NOT_BLOCKED);

                // when
                submit.commit();
                completion.commit();

                // then
                assertClaimedOnly("pay-2");
                assertHeadOf("order-1", "pay-2");
            }
        }

        @Test
        @DisplayName("접수가 열려 있어도 완료는 기다리지 않는다 — 워커가 다른 키의 결과도 멈추지 않고 쓴다")
        void aCompletionDoesNotWaitForAnOpenSubmit() {
            try (OtherTransaction<CommandReceipt> submit = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-2")));
                 OtherTransaction<Boolean> completion = startAfter(submit, storage.transactions(),
                         HeadRaceTest.this::complete)) {
                completion.assertFinishedWithin(NOT_BLOCKED);

                // when
                completion.commit();
                submit.commit();

                // then
                assertClaimedOnly("pay-2");
                assertHeadOf("order-1", "pay-2");
            }
        }
    }

    @Nested
    @Order(2)
    @DisplayName("claim이 끝난 맨 앞을 넘길 때")
    class PassingOn {

        @Test
        @DisplayName("앱이 그 키 행을 쥐고 있으면 그 키만 미루고 다른 키는 내보낸다. 앱이 커밋하면 다음 claim이 넘긴다")
        void aKeyHeldByAnOpenSubmitIsPassedOnLater() {
            // given: pay-1이 끝났고, 다른 키의 pay-9가 기다리고, 앱이 order-1에 pay-2를 접수하는 중이다
            complete();
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-9", "order-9")));
            try (OtherTransaction<CommandReceipt> submit = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-2")))) {
                submit.awaitWork();

                // when
                List<String> whileHeld = claimedNow();
                submit.commit();
                List<String> afterCommit = claimedNow();

                // then
                assertThat(whileHeld).as("앱이 order-1 키 행을 쥔 동안 잡힌 명령").containsExactly("pay-9");
                assertThat(afterCommit).as("앱이 커밋한 뒤 잡힌 명령").containsExactly("pay-2");
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("앱 트랜잭션이 더 높은 격리 수준이면")
    class HigherIsolation {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("완료와 겹친 접수는 그대로 커밋되고, 다음 claim이 뒤 명령을 잡는다")
        void aSubmitOverlappingACompletionCommits(AppIsolation isolation) {
            // given
            Transactions app = appTransactions(isolation);
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(),
                    HeadRaceTest.this::complete);
                 OtherTransaction<CommandReceipt> submit = startAfter(completion, app,
                         () -> relay.submit(transfer("pay-2")))) {
                submit.awaitWork();

                // when
                completion.commit();
                submit.commit();

                // then
                assertClaimedOnly("pay-2");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("스냅숏을 잡은 뒤 claim이 맨 앞을 없앤 키에 접수하면 재시도할 수 있는 실패로 끝나고, 다시 접수하면 맨 앞이 된다")
        void aSubmitOnAKeyWhoseHeadWasPassedOnAfterItsSnapshotEndsInARetryableFailure(AppIsolation isolation) {
            // given: 앱의 스냅숏에서 pay-1은 아직 처리 중인 맨 앞이다. 그 뒤 pay-1이 끝나고 claim이 맨 앞을 없앴다
            Transactions app = appTransactions(isolation);
            CountDownLatch snapshotTaken = new CountDownLatch(1);
            CountDownLatch submitNow = new CountDownLatch(1);
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(),
                    HeadRaceTest.this::complete)) {
                completion.awaitWork();
                try (OtherTransaction<CommandReceipt> submit = OtherTransaction.start(app, () -> {
                    storage.sql().sql("SELECT count(*) FROM ledger_command").query(Long.class).single();
                    snapshotTaken.countDown();
                    await(submitNow);
                    return relay.submit(transfer("pay-2"));
                })) {
                    await(snapshotTaken);
                    completion.commit();
                    assertThat(claimedNow()).as("넘기는 claim이 잡은 명령").isEmpty();
                    assertNoHeadOf("order-1");

                    // when
                    submitNow.countDown();

                    // then
                    assertEndedInARetryableFailure(submit);
                }
            }
            app.inTransaction(() -> relay.submit(transfer("pay-2")));
            assertHeadOf("order-1", "pay-2");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("앱과 핸들러가 같은 주문 행을 고치면")
    class SharedOrderRow {

        CommandLifecycle relayThatMarksTheOrder;

        @BeforeEach
        void givenAHandlerThatMarksTheOrderPaid() {
            storage.sql().sql("CREATE TABLE IF NOT EXISTS app_order (id text PRIMARY KEY, status text NOT NULL)").update();
            storage.sql().sql("TRUNCATE app_order").update();
            storage.sql().sql("INSERT INTO app_order (id, status) VALUES ('1', '결제 대기')").update();
            relayThatMarksTheOrder = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                    new SequentialTransferIds(), outcome -> markOrder("결제 완료"),
                    new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        }

        @Test
        @DisplayName("주문 행을 고친 뒤 접수해도 교착하지 않는다 — 결과를 쓰는 쪽은 키 행을 쥐지 않고 주문 행만 기다린다")
        void anAppThatMarksTheOrderThenSubmitsDoesNotDeadlockWithTheHandler() throws Exception {
            CountDownLatch orderMarked = new CountDownLatch(1);
            CountDownLatch submitNow = new CountDownLatch(1);
            try (OtherTransaction<CommandReceipt> app = OtherTransaction.start(storage.transactions(), () -> {
                markOrder("결제 중");
                orderMarked.countDown();
                await(submitNow);
                return relay.submit(transfer("pay-2"));
            })) {
                // given: 앱이 주문 행을 쥐었고, pay-1의 핸들러가 그 주문 행을 기다린다
                await(orderMarked);
                CompletableFuture<WriteResult> completion = CompletableFuture
                        .supplyAsync(() -> relayThatMarksTheOrder.complete(pay1, Created));
                assertStillRunningAfter(completion, BLOCKED);

                // when: 앱이 같은 키에 접수하고 커밋한다
                submitNow.countDown();
                app.awaitWork();
                app.commit();

                // then
                assertThat(completion.get(10, TimeUnit.SECONDS)).as("pay-1의 결과 쓰기").isEqualTo(new WriteResult.Written());
                assertThat(orderStatus()).as("핸들러가 마지막에 쓴 주문 상태").isEqualTo("결제 완료");
            }
        }

        @Test
        @DisplayName("접수한 뒤 주문 행을 고쳐도 교착하지 않는다")
        void anAppThatSubmitsThenMarksTheOrderDoesNotDeadlockWithTheHandler() throws Exception {
            CountDownLatch submitted = new CountDownLatch(1);
            CountDownLatch markNow = new CountDownLatch(1);
            try (OtherTransaction<CommandReceipt> app = OtherTransaction.start(storage.transactions(), () -> {
                CommandReceipt receipt = relay.submit(transfer("pay-2"));
                submitted.countDown();
                await(markNow);
                markOrder("결제 중");
                return receipt;
            })) {
                // given: 앱이 같은 키에 접수했고, pay-1의 결과를 쓰기 시작한다
                await(submitted);
                CompletableFuture<WriteResult> completion = CompletableFuture
                        .supplyAsync(() -> relayThatMarksTheOrder.complete(pay1, Created));

                // when: 앱이 주문 행을 고치고 커밋한다
                markNow.countDown();
                app.awaitWork();
                app.commit();

                // then
                assertThat(completion.get(10, TimeUnit.SECONDS)).as("pay-1의 결과 쓰기").isEqualTo(new WriteResult.Written());
            }
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private void markOrder(String status) {
        storage.sql().sql("UPDATE app_order SET status = :status WHERE id = '1'").param("status", status).update();
    }

    private String orderStatus() {
        return storage.sql().sql("SELECT status FROM app_order WHERE id = '1'").query(String.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("10초 안에 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리다 인터럽트되었다", e);
        }
    }

    private static void assertStillRunningAfter(CompletableFuture<?> work, Duration observation) {
        try {
            Object done = work.get(observation.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError(observation.toMillis() + "ms 안에 끝났다(" + done + "): 막혀 있기를 기대했다");
        } catch (java.util.concurrent.TimeoutException expected) {
            // 막혀 있다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리다 인터럽트되었다", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("막혀 있기를 기대했는데 실패로 끝났다", e.getCause());
        }
    }

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

    /** 지금 claim이 잡는 명령. */
    private List<String> claimedNow() {
        return relay.claim(10, LEASE).claimed().stream().map(ClaimedCommand::commandId).toList();
    }

    private void assertClaimedOnly(String commandId) {
        assertThat(claimedNow()).as("다음 claim이 잡은 명령").containsExactly(commandId);
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
        return transfer(commandId, "order-1");
    }

    private static LedgerCommand transfer(String commandId, String orderingKey) {
        return LedgerCommand.transfer(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }

    /** 접수가 일하는 중에 또는 커밋할 때 직렬화 실패로 끝났다. 앱은 이 SQLSTATE를 보고 재시도한다. */
    private static void assertEndedInARetryableFailure(OtherTransaction<?> submit) {
        Throwable failure = catchThrowable(() -> {
            submit.awaitWork();
            submit.commit();
        });
        assertThat(sqlState(failure)).as("접수의 실패 SQLSTATE (실패: %s)", failure).isEqualTo(SERIALIZATION_FAILURE);
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
