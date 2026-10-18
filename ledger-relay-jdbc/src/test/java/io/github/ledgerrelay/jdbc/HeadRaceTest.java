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
 * When the completion of the head and the acceptance of a later command overlap on the same key. Completion does not
 * lock the ordering key row, so neither waits for the other. The next claim locks the key row and passes on the
 * finished head; if the app holds that key row, only that key is delayed. At no isolation level does the head vanish
 * and leave the key stuck forever.
 */
@DisplayName("When a completion and a submit overlap on the same key")
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
    @DisplayName("At READ COMMITTED")
    class ReadCommitted {

        @Test
        @DisplayName("A submit does not wait for an open completion, and after both commit the next claim claims the later command")
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
        @DisplayName("A completion does not wait for an open submit — the worker keeps writing results of other keys too")
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
    @DisplayName("When the claim passes on a finished head")
    class PassingOn {

        @Test
        @DisplayName("If the app holds that key row, only that key is delayed and other keys go out. After the app commits, the next claim passes it on")
        void aKeyHeldByAnOpenSubmitIsPassedOnLater() {
            // given: pay-1 has finished, pay-9 on another key is waiting, and the app is submitting pay-2 on order-1
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
                assertThat(whileHeld).as("commands claimed while the app holds the order-1 key row").containsExactly("pay-9");
                assertThat(afterCommit).as("commands claimed after the app commits").containsExactly("pay-2");
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When the app transaction runs at a higher isolation level")
    class HigherIsolation {

        @ParameterizedTest(name = "{0}")
        @EnumSource(AppIsolation.class)
        @DisplayName("A submit that overlaps a completion commits as is, and the next claim claims the later command")
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
        @DisplayName("Submitting on a key whose head the claim removed after the snapshot was taken ends in a retryable failure, and submitting again makes it the head")
        void aSubmitOnAKeyWhoseHeadWasPassedOnAfterItsSnapshotEndsInARetryableFailure(AppIsolation isolation) {
            // given: in the app's snapshot pay-1 is still the in-flight head. After that pay-1 finished and the claim removed the head
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
                    assertThat(claimedNow()).as("commands claimed by the passing-on claim").isEmpty();
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
    @DisplayName("When the app and the handler update the same order row")
    class SharedOrderRow {

        CommandLifecycle relayThatMarksTheOrder;

        @BeforeEach
        void givenAHandlerThatMarksTheOrderPaid() {
            storage.sql().sql("CREATE TABLE IF NOT EXISTS app_order (id text PRIMARY KEY, status text NOT NULL)").update();
            storage.sql().sql("TRUNCATE app_order").update();
            storage.sql().sql("INSERT INTO app_order (id, status) VALUES ('1', 'awaiting payment')").update();
            relayThatMarksTheOrder = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                    new SequentialTransferIds(), outcome -> markOrder("paid"),
                    new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        }

        @Test
        @DisplayName("Submitting after updating the order row does not deadlock — writing the result holds no key row and waits only for the order row")
        void anAppThatMarksTheOrderThenSubmitsDoesNotDeadlockWithTheHandler() throws Exception {
            CountDownLatch orderMarked = new CountDownLatch(1);
            CountDownLatch submitNow = new CountDownLatch(1);
            try (OtherTransaction<CommandReceipt> app = OtherTransaction.start(storage.transactions(), () -> {
                markOrder("paying");
                orderMarked.countDown();
                await(submitNow);
                return relay.submit(transfer("pay-2"));
            })) {
                // given: the app holds the order row, and the handler of pay-1 waits for that order row
                await(orderMarked);
                CompletableFuture<WriteResult> completion = CompletableFuture
                        .supplyAsync(() -> relayThatMarksTheOrder.complete(pay1, Created));
                assertStillRunningAfter(completion, BLOCKED);

                // when: the app submits on the same key and commits
                submitNow.countDown();
                app.awaitWork();
                app.commit();

                // then
                assertThat(completion.get(10, TimeUnit.SECONDS)).as("writing the result of pay-1").isEqualTo(new WriteResult.Written());
                assertThat(orderStatus()).as("order state last written by the handler").isEqualTo("paid");
            }
        }

        @Test
        @DisplayName("Updating the order row after submitting does not deadlock")
        void anAppThatSubmitsThenMarksTheOrderDoesNotDeadlockWithTheHandler() throws Exception {
            CountDownLatch submitted = new CountDownLatch(1);
            CountDownLatch markNow = new CountDownLatch(1);
            try (OtherTransaction<CommandReceipt> app = OtherTransaction.start(storage.transactions(), () -> {
                CommandReceipt receipt = relay.submit(transfer("pay-2"));
                submitted.countDown();
                await(markNow);
                markOrder("paying");
                return receipt;
            })) {
                // given: the app has submitted on the same key, and writing the result of pay-1 starts
                await(submitted);
                CompletableFuture<WriteResult> completion = CompletableFuture
                        .supplyAsync(() -> relayThatMarksTheOrder.complete(pay1, Created));

                // when: the app updates the order row and commits
                markNow.countDown();
                app.awaitWork();
                app.commit();

                // then
                assertThat(completion.get(10, TimeUnit.SECONDS)).as("writing the result of pay-1").isEqualTo(new WriteResult.Written());
            }
        }
    }

    // ── Vocabulary used only in this file ──────────────────────────────

    private void markOrder(String status) {
        storage.sql().sql("UPDATE app_order SET status = :status WHERE id = '1'").param("status", status).update();
    }

    private String orderStatus() {
        return storage.sql().sql("SELECT status FROM app_order WHERE id = '1'").query(String.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("no signal within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private static void assertStillRunningAfter(CompletableFuture<?> work, Duration observation) {
        try {
            Object done = work.get(observation.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError("finished within " + observation.toMillis() + "ms (" + done + "): expected it to be blocked");
        } catch (java.util.concurrent.TimeoutException expected) {
            // blocked
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("expected it to be blocked, but it failed", e.getCause());
        }
    }

    /** The worker writes the TB result of pay-1. Only the store's conditional write, without a handler. */
    private boolean complete() {
        long submitCount = storage.sql().sql("SELECT submit_count FROM ledger_command WHERE id = :id")
                .param("id", pay1.rowId()).query(Long.class).single();
        CommandRow applied = new CommandRow(StoredState.APPLIED, pay1.leaseToken(), submitCount, Optional.of(Created),
                Optional.of(Created), 0);
        boolean written = storage.store().compareAndSet(new RowChange(pay1.rowId(), StoredState.CLAIMED,
                pay1.leaseToken(), applied, new RowChange.Stamp.Completion()));
        if (!written) {
            throw new IllegalStateException("given setup failed: could not write the result of pay-1");
        }
        return true;
    }

    /** Commands a claim now claims. */
    private List<String> claimedNow() {
        return relay.claim(10, LEASE).claimed().stream().map(ClaimedCommand::commandId).toList();
    }

    private void assertClaimedOnly(String commandId) {
        assertThat(claimedNow()).as("commands claimed by the next claim").containsExactly(commandId);
    }

    private void assertHeadOf(String orderingKey, String commandId) {
        assertThat(heads(orderingKey)).as("head of %s", orderingKey).containsExactly(commandId);
    }

    private void assertNoHeadOf(String orderingKey) {
        assertThat(heads(orderingKey)).as("head of %s", orderingKey).isEmpty();
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

    /** The submit ended in a serialization failure, during its work or at commit. The app retries on this SQLSTATE. */
    private static void assertEndedInARetryableFailure(OtherTransaction<?> submit) {
        Throwable failure = catchThrowable(() -> {
            submit.awaitWork();
            submit.commit();
        });
        assertThat(sqlState(failure)).as("failure SQLSTATE of the submit (failure: %s)", failure).isEqualTo(SERIALIZATION_FAILURE);
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
