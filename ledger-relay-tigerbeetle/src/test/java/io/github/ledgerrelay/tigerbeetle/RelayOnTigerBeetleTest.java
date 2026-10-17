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
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.github.ledgerrelay.core.application.RelayWorker;
import io.github.ledgerrelay.core.application.WorkerSettings;
import io.github.ledgerrelay.core.spi.LedgerClient;
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

/** 메모리 저장소 + 실제 TB 위에서 접수부터 결과까지(03a P3a-11). PG와 TB를 함께 띄우는 확인은 03b 샘플 앱에서 한다. */
@DisplayName("실제 TB 위의 워커 한 바퀴 — 스펙 §5.3, D7, 열린 장애 O3")
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
        relay = new CommandLifecycle(storage.store(), storage.transactions(),
                () -> UInt128.asBigInteger(UInt128.id()), handler, new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
        payer = ledger.openAccount("구매자");
        payee = ledger.openAccount("가맹점");
    }

    @Nested
    @Order(1)
    @DisplayName("앱이 접수한 명령을 워커가 한 바퀴 돌리면")
    class OneCycle {

        RelayWorker worker;

        @BeforeEach
        void givenAWorkerOnTigerBeetle() {
            worker = workerOn(new TigerBeetleLedgerClient(workerClient));
        }

        @Test
        @DisplayName("이체는 돈을 옮기고 적용됨으로 끝나며, 핸들러가 한 번 실행된다")
        void aTransferMovesMoneyAndEndsApplied() {
            // given
            accepted(transfer("pay-1", payer, payee, 500));

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertThat(handler.completedFor("pay-1")).as("pay-1의 핸들러 실행").hasSize(1);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("예약을 보낸 뒤 그 확정을 보내면 예약 금액 전부가 확정된다")
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
        @DisplayName("잔액이 부족한 지갑의 이체는 거절됨으로 끝나고, 돈은 움직이지 않는다")
        void insufficientFundsEndRejected() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("지갑");
            accepted(transfer("pay-1", wallet, payee, 100));

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.REJECTED);
            assertThat(relay.status("pay-1").orElseThrow().rejection().orElseThrow().tbCode())
                    .as("pay-1의 거절 코드").isEqualTo(ExceedsCredits);
            assertNothingMoved(ledger, wallet);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("TB 응답이 유실되면 (O3)")
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
        @DisplayName("요청은 TB에 닿고 응답만 사라졌으면, 같은 이체 ID로 다시 보내 이미 기록됨으로 끝나고 돈은 한 번만 움직인다")
        void aLostReplyIsRetriedWithTheSameTransferIdAndMovesMoneyOnce() {
            // given: 첫 요청은 TB에 닿지만 응답은 워커에게 오지 않는다
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
            assertThat(losing.transferIdsSent()).as("두 요청의 이체 ID").hasSize(2).containsOnly(losing.transferIdsSent().get(0));
            assertThat(losing.deliveredReplies()).as("두 번째 요청의 TB 결과").containsExactly(List.of(Exists));
        }

        @Test
        @DisplayName("연결이 끊겼으면 timeout으로 되돌리고, 복구된 뒤 다시 보내 적용됨으로 끝나며 돈은 한 번만 움직인다")
        void aCutConnectionIsRetriedAfterItIsRestored() throws Exception {
            // given: 프록시 뒤의 TB에 계좌를 열고 연결을 끊었다
            proxied = ProxiedTigerBeetle.start();
            proxiedLedger = new TigerBeetleTestLedger(proxied.newClient());
            Account buyer = proxiedLedger.openAccount("구매자");
            Account shop = proxiedLedger.openAccount("가맹점");
            com.tigerbeetle.Client throughProxy = proxied.newClient();
            try {
                RelayWorker worker = workerOn(new TigerBeetleLedgerClient(throughProxy));
                proxied.cutConnection();
                accepted(transfer("pay-1", buyer, shop, 500));
                worker.runOnce();
                assertState("pay-1", State.IN_PROGRESS);

                // when: 연결을 복구하고, 적용될 때까지 backoff를 지나며 다시 돈다. TB 클라이언트가 다시 붙고 끊기기 전의
                // 요청을 먼저 보내는 데 걸리는 시간은 정해져 있지 않아, submit-timeout 한 번에 기대지 않는다
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

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

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
        assertThat(relay.status(commandId).orElseThrow().state()).as("%s의 조회 상태", commandId).isEqualTo(expected);
    }

    /**
     * 첫 요청은 실제 TB에 보내 기록될 때까지 기다리지만, 워커에게는 끝나지 않는 응답을 돌려준다. 요청은 TB에 닿았는데
     * 응답만 사라진 상황이다. 그다음 요청부터는 그대로 전하고, 받은 결과를 남긴다.
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

        synchronized List<BigInteger> transferIdsSent() {
            return List.copyOf(transferIdsSent);
        }

        synchronized List<List<LedgerResultCode>> deliveredReplies() {
            return List.copyOf(deliveredReplies);
        }
    }
}
