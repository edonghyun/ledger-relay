package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * When another transaction commits between the SQL statements that settle the head. READ COMMITTED takes a new
 * snapshot per statement, so this race does not show in {@code HeadRaceTest}, where only transaction boundaries
 * interleave. The statements run one by one, and the other side commits in between.
 */
@DisplayName("When another transaction commits between the statements that settle the head")
class HeadStatementRaceTest {

    static final Duration BLOCKED = Duration.ofMillis(300);
    static final Duration NOT_BLOCKED = Duration.ofSeconds(2);
    static final Duration LEASE = Duration.ofSeconds(30);
    static final long GATE = 4242;

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
    @DisplayName("While a submit settles the head")
    class WhileASubmitSettlesTheHead {

        @Test
        @DisplayName("A completion of the head committing between clearing and marking does not fail the submit, and the next claim claims the later command")
        void aCompletionCommittingBetweenTheTwoStatementsDoesNotFailTheSubmit() {
            CountDownLatch cleared = new CountDownLatch(1);
            CountDownLatch markNow = new CountDownLatch(1);
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(), this::complete)) {
                completion.awaitWork();
                try (OtherTransaction<Boolean> submit = OtherTransaction.start(storage.transactions(), () -> {
                    long seq = storage.keys().lockKeyAndTakeSeq("order-1");
                    insertPendingRow("pay-2", seq);
                    storage.sql().sql(JdbcCommandStore.CLEAR_STALE_HEAD).param("key", "order-1").update();
                    cleared.countDown();
                    await(markNow);
                    storage.sql().sql(JdbcCommandStore.MARK_FIRST_UNFINISHED).param("key", "order-1").update();
                    return true;
                })) {
                    // given: the submit has run the clearing statement, and then the completion of pay-1 commits
                    await(cleared);
                    completion.commit();

                    // when
                    markNow.countDown();
                    submit.awaitWork();
                    submit.commit();
                }
            }

            // then
            assertThat(claimedNow()).as("commands claimed by the next claim").containsExactly("pay-2");
        }

        private boolean complete() {
            return HeadStatementRaceTest.this.complete(pay1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("While a claim passes on a finished head")
    class WhileAClaimPassesOn {

        @Test
        @DisplayName("A late pass-on that picks a key another worker already passed on does not wait for the new head that is writing its result — so it cannot deadlock with the handler")
        void aLatePassOnDoesNotWaitForTheNewHeadBeingCompleted() {
            // given: pay-1 has finished and pay-2 is waiting. A late worker's pass-on has taken its snapshot and is paused
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-2")));
            complete(pay1);
            try (OtherTransaction<Boolean> gate = OtherTransaction.start(storage.transactions(), () -> {
                storage.sql().sql("SELECT pg_advisory_xact_lock(:gate)").param("gate", GATE).query().singleRow();
                return true;
            })) {
                gate.awaitWork();
                try (OtherTransaction<List<String>> late = OtherTransaction.start(storage.transactions(),
                        HeadStatementRaceTest.this::latePassOn)) {
                    late.assertStillWorkingAfter(BLOCKED);
                    // Meanwhile another worker passes on order-1 and claims pay-2, and a transaction writing the result of pay-2 is open
                    ClaimedCommand pay2 = relay.claim(10, LEASE).claimed().get(0);
                    relay.markSubmitted(pay2);
                    try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(),
                            () -> complete(pay2))) {
                        completion.awaitWork();

                        // when
                        gate.commit();

                        // then
                        assertThat(late.assertFinishedWithin(NOT_BLOCKED)).as("keys picked by the late pass-on")
                                .containsExactly("order-1");
                        late.commit();
                        completion.commit();
                    }
                }
            }
        }

        @Test
        @DisplayName("A completion of the new head committing just before marking does not add a second head — so the claim does not break on the unique index")
        void markingDoesNotAddASecondHeadNextToAFinishedOne() {
            // given: the head has passed to pay-2 and pay-3 is waiting. pay-2 has just finished (still holding the mark)
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-2")));
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-3")));
            complete(pay1);
            ClaimedCommand pay2 = relay.claim(10, LEASE).claimed().get(0);
            relay.markSubmitted(pay2);
            complete(pay2);

            // when: a late pass-on that has only cleared pay-1 runs the marking statement
            int marked = storage.transactions().inTransaction(() -> storage.sql().sql(JdbcOrderingKeys.MARK_NEXT_HEADS)
                    .param("keys", List.of("order-1")).update());

            // then
            assertThat(marked).as("rows marked as head").isZero();
            assertThat(claimedNow()).as("commands claimed by the next claim").containsExactly("pay-3");
        }
    }

    /** A late worker's pass-on: the same statements as production code; only the query that picks keys to lock goes through the gate. */
    private List<String> latePassOn() {
        List<String> keys = storage.sql().sql(gated(JdbcOrderingKeys.LOCK_KEYS_TO_PASS_ON)).param("gate", GATE)
                .param("limit", 10).query(String.class).list();
        if (!keys.isEmpty()) {
            storage.sql().sql(JdbcOrderingKeys.REWRITE_KEY_ROWS).param("keys", keys).update();
            storage.sql().sql(JdbcOrderingKeys.CLEAR_FINISHED_HEADS).param("keys", keys).update();
            storage.sql().sql(JdbcOrderingKeys.MARK_NEXT_HEADS).param("keys", keys).update();
        }
        return keys;
    }

    // ── Vocabulary used only in this file ──────────────────────────────

    /** Makes the query wait for advisory lock {@code :gate} after taking its snapshot and before reading finished heads. */
    private static String gated(String query) {
        String gated = query.replace("WHERE c.is_head",
                "WHERE (SELECT true FROM pg_advisory_xact_lock_shared(:gate)) AND c.is_head");
        if (gated.equals(query)) {
            throw new IllegalStateException("given setup failed: the query has no place to insert the gate");
        }
        return gated;
    }

    /** The worker writes the TB result of that command. Only the store's conditional write, without a handler. */
    private boolean complete(ClaimedCommand claimed) {
        long submitCount = storage.sql().sql("SELECT submit_count FROM ledger_command WHERE id = :id")
                .param("id", claimed.rowId()).query(Long.class).single();
        CommandRow applied = new CommandRow(StoredState.APPLIED, claimed.leaseToken(), submitCount, Optional.of(Created),
                Optional.of(Created), 0);
        if (!storage.store().compareAndSet(new RowChange(claimed.rowId(), StoredState.CLAIMED, claimed.leaseToken(),
                applied, new RowChange.Stamp.Completion()))) {
            throw new IllegalStateException("given setup failed: could not write the result of " + claimed.commandId());
        }
        return true;
    }

    /** Inserts the same pending row a submit inserts, without settling the head. */
    private void insertPendingRow(String commandId, long seq) {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state)
                VALUES (:id, 900 + :seq, 'order-1', :seq, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'PENDING')
                """).param("id", commandId).param("seq", seq).update();
    }

    private List<String> claimedNow() {
        return relay.claim(10, LEASE).claimed().stream().map(ClaimedCommand::commandId).toList();
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

    private static LedgerCommand transfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }
}
