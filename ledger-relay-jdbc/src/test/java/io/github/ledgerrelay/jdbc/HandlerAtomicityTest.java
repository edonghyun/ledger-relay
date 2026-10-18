package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Outcome handler that writes to the DB — G6")
class HandlerAtomicityTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration BACKOFF = Duration.ofSeconds(5);

    PostgresStorage storage;
    WalletEventHandler handler;
    CommandLifecycle relay;
    ClaimedCommand pay1;

    @BeforeEach
    void givenPay1IsClaimedAndSubmitted() {
        storage = PostgresStorage.withDataSourceTransactions();
        storage.sql().sql("CREATE TABLE IF NOT EXISTS wallet_event (command_id text PRIMARY KEY, state text NOT NULL)")
                .update();
        storage.sql().sql("TRUNCATE wallet_event").update();
        handler = new WalletEventHandler(storage.dataSource());
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(BACKOFF, BACKOFF));
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1").orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build()));
        pay1 = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(pay1);
    }

    @Nested
    @Order(1)
    @DisplayName("When the handler runs to completion")
    class WhenTheHandlerCompletes {

        @Test
        @DisplayName("The result and the app table row are both kept")
        void theResultAndTheAppRowCommitTogether() {
            // when
            relay.complete(pay1, LedgerResultCode.Created);

            // then
            assertQueryState("pay-1", State.APPLIED);
            assertWalletEvents("pay-1", "APPLIED");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When the handler throws after writing to the app table")
    class WhenTheHandlerThrowsAfterWriting {

        @Test
        @DisplayName("Neither the result nor the app table row is kept — nothing is left half done")
        void neitherTheResultNorTheAppRowRemains() {
            // given
            handler.failAfterWriteOnce();

            // when
            relay.complete(pay1, LedgerResultCode.Created);

            // then
            assertQueryState("pay-1", State.IN_PROGRESS);
            assertNoWalletEvent("pay-1");
        }

        @Test
        @DisplayName("Claiming again and completing with the same result ends as applied with exactly one app table row — the handler's effect is kept once")
        void retryingLeavesExactlyOneAppRow() {
            // given
            handler.failAfterWriteOnce();
            relay.complete(pay1, LedgerResultCode.Created);
            storage.elapse(BACKOFF);
            ClaimedCommand again = relay.claim(10, LEASE).claimed().get(0);

            // when
            relay.complete(again, again.knownResult().orElseThrow());

            // then
            assertQueryState("pay-1", State.APPLIED);
            assertWalletEvents("pay-1", "APPLIED");
        }
    }

    private void assertQueryState(String commandId, State expected) {
        assertThat(relay.status(commandId).orElseThrow().state()).as("query state of %s", commandId).isEqualTo(expected);
    }

    private void assertWalletEvents(String commandId, String... states) {
        assertThat(walletEvents(commandId)).as("wallet_event rows of %s", commandId).containsExactly(states);
    }

    private void assertNoWalletEvent(String commandId) {
        assertThat(walletEvents(commandId)).as("expected no wallet_event row of %s", commandId).isEmpty();
    }

    private List<String> walletEvents(String commandId) {
        return storage.sql().sql("SELECT state FROM wallet_event WHERE command_id = :id")
                .param("id", commandId).query(String.class).list();
    }

    /** Handler stand-in that writes the result to the app table wallet_event. command_id is the primary key, so a second write fails. */
    private static final class WalletEventHandler implements LedgerOutcomeHandler {

        private final DataSource dataSource;
        private boolean failAfterWrite;

        WalletEventHandler(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void failAfterWriteOnce() {
            failAfterWrite = true;
        }

        @Override
        public void onCompleted(CommandStatus outcome) {
            // Writes on the transaction connection Spring has bound: one transaction with writing the result
            JdbcClient.create(dataSource).sql("INSERT INTO wallet_event (command_id, state) VALUES (:id, :state)")
                    .param("id", outcome.commandId()).param("state", outcome.state().name()).update();
            if (failAfterWrite) {
                failAfterWrite = false;
                throw new IllegalStateException("the handler fails on purpose after writing");
            }
        }
    }
}
