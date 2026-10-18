package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static io.github.ledgerrelay.core.LedgerResultCode.Exists;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertPostedAndReleased;
import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.ReconciliationReport;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.github.ledgerrelay.core.application.RelayOperator;
import io.github.ledgerrelay.core.application.RelayWorker;
import io.github.ledgerrelay.core.application.WorkerSettings;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.LedgerTransfer;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.ProxiedTigerBeetle;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * From acceptance to result on an in-memory store and a real TB. Running PG and TB together is checked by the
 * integration test in the spring-boot module and by the sample app.
 */
@DisplayName("One worker cycle on a real TB — F23")
class RelayOnTigerBeetleTest {

    static final Duration BACKOFF_MAX = Duration.ofSeconds(1);
    static final WorkerSettings SETTINGS =
            new WorkerSettings(128, 1024, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMillis(100));

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    static TigerBeetleTestLedger ledger;
    static com.tigerbeetle.Client workerClient;

    InMemoryStorage storage;
    RecordingHandler handler;
    CommandLifecycle relay;
    Account payer;
    Account payee;

    @BeforeAll
    static void start() {
        TB.start();
        ledger = new TigerBeetleTestLedger(TB.newClient());
        workerClient = TB.newClient();
    }

    @AfterAll
    static void stop() {
        if (workerClient != null) {
            workerClient.close();
        }
        if (ledger != null) {
            ledger.close();
        }
        TB.stop();
    }

    @BeforeEach
    void givenAnEmptyRelay() {
        TB.assertAlive();
        storage = InMemoryStorage.create();
        handler = new RecordingHandler();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                () -> UInt128.asBigInteger(UInt128.id()), handler, new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
        payer = ledger.openAccount("buyer");
        payee = ledger.openAccount("merchant");
    }

    @Nested
    @Order(1)
    @DisplayName("When the worker runs a cycle over commands the app accepted")
    class OneCycle {

        RelayWorker worker;

        @BeforeEach
        void givenAWorkerOnTigerBeetle() {
            worker = workerOn(new TigerBeetleLedgerClient(workerClient));
        }

        @Test
        @DisplayName("A transfer moves money and ends as applied, and the handler runs once")
        void aTransferMovesMoneyAndEndsApplied() {
            // given
            accepted(transfer("pay-1", payer, payee, 500));

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertThat(handler.completedFor("pay-1")).as("handler runs for pay-1").hasSize(1);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("Sending a reservation and then its post posts the full reserved amount")
        void aReservationThenItsPostCaptureTheAmount() {
            // given
            accepted(LedgerCommand.reserve("hold-1").orderingKey("order-1").debit(accountIdOf(payer))
                    .credit(accountIdOf(payee)).amount(500).ledger(1).code(1).timeoutSeconds(60).build());
            worker.runOnce();
            accepted(LedgerCommand.post("capture-1").orderingKey("order-1").reservation("hold-1").build());

            // when
            worker.runOnce();

            // then
            assertState("hold-1", State.APPLIED);
            assertState("capture-1", State.APPLIED);
            assertPostedAndReleased(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("A transfer from a wallet with insufficient balance ends as rejected, and no money moves")
        void insufficientFundsEndRejected() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("wallet");
            accepted(transfer("pay-1", wallet, payee, 100));

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.REJECTED);
            assertThat(relay.status("pay-1").orElseThrow().rejection().orElseThrow().tbCode())
                    .as("rejection code of pay-1").isEqualTo(ExceedsCredits);
            assertNothingMoved(ledger, wallet);
        }

        @Test
        @DisplayName("Reconciliation after a mix of transfer, reservation, post and rejection finds no mismatch")
        void reconcilingAfterRealTrafficFindsNoMismatch() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("wallet");
            accepted(transfer("pay-1", payer, payee, 500));
            accepted(LedgerCommand.reserve("hold-1").orderingKey("order-2").debit(accountIdOf(payer))
                    .credit(accountIdOf(payee)).amount(300).ledger(1).code(1).timeoutSeconds(60).build());
            accepted(LedgerCommand.transfer("pay-2").orderingKey("order-3").debit(accountIdOf(wallet))
                    .credit(accountIdOf(payee)).amount(100).ledger(1).code(1).build());
            worker.runOnce();
            accepted(LedgerCommand.post("capture-1").orderingKey("order-2").reservation("hold-1").build());
            worker.runOnce();
            RelayOperator operator = new RelayOperator(storage.store(), storage.transactions(), relay,
                    new TigerBeetleLedgerClient(workerClient), Duration.ofSeconds(10));

            // when
            ReconciliationReport report = operator.reconcile(0, 100);

            // then
            assertThat(report.mismatches()).as("mismatches").isEmpty();
            assertThat(report.checked()).as("commands checked").isEqualTo(4);
            assertThat(report.matched()).as("matched").isEqualTo(4);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a TB reply is lost — F23")
    class LostReplies {

        ProxiedTigerBeetle proxied;
        TigerBeetleTestLedger proxiedLedger;

        @AfterEach
        void closeTheProxy() {
            if (proxiedLedger != null) {
                proxiedLedger.close();
            }
            if (proxied != null) {
                proxied.close();
            }
        }

        @Test
        @DisplayName("If the request reached TB and only the reply was lost, resending with the same transfer id ends as already recorded and money moves only once")
        void aLostReplyIsRetriedWithTheSameTransferIdAndMovesMoneyOnce() {
            // given: the first request reaches TB, but its reply never reaches the worker
            ReplyLosingLedger losing = new ReplyLosingLedger(new TigerBeetleLedgerClient(workerClient));
            RelayWorker worker = workerOn(losing);
            accepted(transfer("pay-1", payer, payee, 500));
            worker.runOnce();
            storage.elapse(BACKOFF_MAX);

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertMovedExactly(ledger, payer, payee, 500);
            assertThat(losing.transferIdsSent()).as("transfer ids of the two requests").hasSize(2).containsOnly(losing.transferIdsSent().get(0));
            assertThat(losing.deliveredReplies()).as("TB results of the second request").containsExactly(List.of(Exists));
        }

        @Test
        @DisplayName("If the connection is cut, the command is returned on timeout, resent after recovery, ends as applied, and money moves only once")
        void aCutConnectionIsRetriedAfterItIsRestored() throws Exception {
            // given: accounts are opened on the TB behind the proxy, and the connection is cut
            proxied = ProxiedTigerBeetle.start();
            proxiedLedger = new TigerBeetleTestLedger(proxied.newClient());
            Account buyer = proxiedLedger.openAccount("buyer");
            Account shop = proxiedLedger.openAccount("merchant");
            com.tigerbeetle.Client throughProxy = proxied.newClient();
            try {
                RelayWorker worker = workerOn(new TigerBeetleLedgerClient(throughProxy));
                proxied.cutConnection();
                accepted(transfer("pay-1", buyer, shop, 500));
                worker.runOnce();
                assertState("pay-1", State.IN_PROGRESS);

                // when: the connection is restored, and cycles run again past backoff until applied. How long the TB
                // client takes to reconnect and send the request from before the cut is not fixed, so this does not
                // rely on a single submit-timeout
                proxied.restoreConnection();
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                do {
                    storage.elapse(BACKOFF_MAX);
                    worker.runOnce();
                } while (relay.status("pay-1").orElseThrow().state() != State.APPLIED && System.nanoTime() < deadline);

                // then
                assertState("pay-1", State.APPLIED);
                assertMovedExactly(proxiedLedger, buyer, shop, 500);
            } finally {
                throughProxy.close();
            }
        }
    }

    // ── Vocabulary used only in this file ──────────────────────────────────────────

    private RelayWorker workerOn(LedgerClient client) {
        return new RelayWorker(relay, client, new InFlightLimit(SETTINGS.maxInFlight()), SETTINGS, WorkerEvents.NONE);
    }

    private void accepted(LedgerCommand command) {
        storage.transactions().inTransaction(() -> relay.submit(command));
    }

    private static LedgerCommand transfer(String commandId, Account from, Account to, long amount) {
        return LedgerCommand.transfer(commandId).orderingKey("order-1").debit(accountIdOf(from))
                .credit(accountIdOf(to)).amount(amount).ledger(1).code(1).build();
    }

    private static BigInteger accountIdOf(Account account) {
        return UInt128.asBigInteger(account.id());
    }

    private void assertState(String commandId, State expected) {
        assertThat(relay.status(commandId).orElseThrow().state()).as("queried state of %s", commandId).isEqualTo(expected);
    }

    /**
     * Sends the first request to the real TB and waits until it is recorded, but returns a reply that never completes
     * to the worker: the request reached TB and only the reply was lost. Later requests pass through unchanged, and
     * their results are kept.
     */
    private static final class ReplyLosingLedger implements LedgerClient {

        private final LedgerClient real;
        private final List<BigInteger> transferIdsSent = new ArrayList<>();
        private final List<List<LedgerResultCode>> deliveredReplies = new ArrayList<>();
        private boolean lostOne;

        ReplyLosingLedger(LedgerClient real) {
            this.real = real;
        }

        @Override
        public synchronized CompletableFuture<List<LedgerResultCode>> submit(List<Submission> batch) {
            batch.forEach(submission -> transferIdsSent.add(submission.transferId()));
            if (!lostOne) {
                lostOne = true;
                real.submit(batch).join();
                return new CompletableFuture<>();
            }
            return real.submit(batch).thenApply(results -> {
                synchronized (this) {
                    deliveredReplies.add(results);
                }
                return results;
            });
        }

        @Override
        public CompletableFuture<List<LedgerTransfer>> lookup(List<BigInteger> transferIds) {
            return real.lookup(transferIds);
        }

        synchronized List<BigInteger> transferIdsSent() {
            return List.copyOf(transferIdsSent);
        }

        synchronized List<List<LedgerResultCode>> deliveredReplies() {
            return List.copyOf(deliveredReplies);
        }
    }
}
