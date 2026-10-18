package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import io.github.ledgerrelay.core.testing.TestTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * The mark (is_head) on the command whose turn it is on each key. It is set on insert if the key has no head, and the
 * next claim passes it on when the command finishes. For the claim query to read only heads, without scanning the
 * backlog of a blocked key, this mark must always match the rows.
 */
@DisplayName("Head mark per key")
class HeadBookkeepingTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration BLOCKED = Duration.ofMillis(300);

    PostgresStorage storage;
    CommandLifecycle relay;

    @BeforeEach
    void givenAnEmptyRelay() {
        storage = PostgresStorage.withDataSourceTransactions();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
    }

    @AfterEach
    void theHeadsStayConsistent() {
        storage.assertHeadsConsistent();
    }

    @Nested
    @Order(1)
    @DisplayName("On submit")
    class Submitting {

        @Test
        @DisplayName("The first command of a key is its head")
        void theFirstCommandOfAKeyIsItsHead() {
            // when
            submit(transfer("pay-1"));

            // then
            assertHeadOf("order-1", "pay-1");
        }

        @Test
        @DisplayName("A later command waits behind the head")
        void aLaterCommandWaitsBehindTheHead() {
            // when
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));

            // then
            assertHeadOf("order-1", "pay-1");
        }

        @Test
        @DisplayName("A rolled-back submit leaves nothing")
        void aRolledBackSubmitLeavesNothing() {
            // when
            assertThatThrownBy(() -> storage.transactions().inTransaction(() -> {
                relay.submit(transfer("pay-1"));
                throw new IllegalStateException("the app rolls back");
            })).isInstanceOf(IllegalStateException.class);

            // then
            assertNoHeadOf("order-1");
        }

        @Test
        @DisplayName("Submitting the same command again keeps the head")
        void aDuplicateSubmitKeepsTheHead() {
            // when
            submit(transfer("pay-1"));
            submit(transfer("pay-1"));

            // then
            assertHeadOf("order-1", "pay-1");
        }

        @Test
        @DisplayName("A submit behind a finished head takes over the head — it already holds the key row")
        void aSubmitBehindAFinishedHeadTakesTheHead() {
            // given
            submit(transfer("pay-1"));
            completeTheHead();

            // when
            submit(transfer("pay-2"));

            // then
            assertHeadOf("order-1", "pay-2");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When the head finishes")
    class Finishing {

        @Test
        @DisplayName("The finished command keeps the mark until the next claim — completion does not lock the key row")
        void theFinishedHeadKeepsTheMarkUntilTheNextClaim() {
            // given
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));

            // when
            completeTheHead();

            // then
            assertHeadOf("order-1", "pay-1");
        }

        @Test
        @DisplayName("The next claim passes the head on to the next command and claims it in the same claim")
        void theNextClaimPassesTheHeadOnAndClaimsIt() {
            // given
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));
            completeTheHead();

            // when
            List<String> claimed = claimedNow();

            // then
            assertThat(claimed).as("commands claimed by the next claim").containsExactly("pay-2");
            assertHeadOf("order-1", "pay-2");
        }

        @Test
        @DisplayName("When the last command finishes, there is no head after the next claim")
        void finishingTheLastLeavesNoHead() {
            // given
            submit(transfer("pay-1"));
            completeTheHead();

            // when
            claimedNow();

            // then
            assertNoHeadOf("order-1");
        }

        @Test
        @DisplayName("A halted head keeps blocking the key, and after the operator abandons it the next claim claims the next command — F9")
        void aHaltedHeadStillBlocksAndAbandoningPassesItOn() {
            // given: capture-1 refers to a reservation later on the same key and halts in the claim
            insertPastTheChecks(reserveRow("hold-1", 2));
            insertPastTheChecks(postRow("capture-1", 1, "hold-1"));
            assertThat(relay.claim(10, LEASE).halted()).as("halted commands").containsExactly("capture-1");
            assertHeadOf("order-1", "capture-1");
            relay.abandonHalted("capture-1");

            // when
            List<String> claimed = claimedNow();

            // then
            assertThat(claimed).as("commands claimed after abandon").containsExactly("hold-1");
            assertHeadOf("order-1", "hold-1");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("After editing the DB by hand")
    class AfterHandEdits {

        @Test
        @DisplayName("Recomputing restores the head")
        void rebuildRepairsHandEditedRows() {
            // given
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));
            storage.sql().sql("UPDATE ledger_command SET is_head = false").update();

            // when
            storage.rebuildHeads();

            // then
            assertHeadOf("order-1", "pay-1");
        }

        @Test
        @DisplayName("Recomputing waits for an open submit to end — so a submit cannot change the head during the computation")
        void rebuildWaitsForAnOpenSubmit() {
            // given: the submit of pay-2 is open
            submit(transfer("pay-1"));
            try (OtherTransaction<CommandReceipt> open = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-2")));
                 OtherTransaction<Boolean> rebuild = startAfter(open, () -> {
                     storage.rebuildHeads();
                     return true;
                 })) {
                rebuild.assertStillWorkingAfter(BLOCKED);

                // when
                open.commit();
                rebuild.awaitWork();
                rebuild.commit();
            }

            // then
            assertHeadOf("order-1", "pay-1");
        }
    }

    // ── Vocabulary used only in this file ──────────────────────────────

    private <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, java.util.function.Supplier<T> work) {
        earlier.awaitWork();
        return OtherTransaction.start(storage.transactions(), work);
    }

    private void submit(LedgerCommand command) {
        storage.transactions().inTransaction(() -> relay.submit(command));
    }

    /** Commands a claim now claims. */
    private List<String> claimedNow() {
        return relay.claim(10, LEASE).claimed().stream().map(ClaimedCommand::commandId).toList();
    }

    private void completeTheHead() {
        ClaimedCommand claimed = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(claimed);
        relay.complete(claimed, Created);
    }

    private void insertPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given setup failed: " + row.commandId() + " already existed");
        }
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
        return LedgerCommand.transfer(commandId).orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }

    private static NewCommand reserveRow(String commandId, long seq) {
        return new NewCommand(commandId, "order-1", seq, Optional.empty(), TestTransferIds.transferIdFor("order-1", seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    private static NewCommand postRow(String commandId, long seq, String reservationCommandId) {
        return new NewCommand(commandId, "order-1", seq, Optional.of(reservationCommandId),
                TestTransferIds.transferIdFor("order-1", seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(TestTransferIds.transferIdFor("order-1", 2)), 0, 0));
    }
}
