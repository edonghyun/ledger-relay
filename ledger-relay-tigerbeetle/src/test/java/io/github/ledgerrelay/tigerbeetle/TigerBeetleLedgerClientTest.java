package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static io.github.ledgerrelay.core.LedgerResultCode.Exists;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertPostedAndReleased;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertReserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerTransfer;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.StoredTransfer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("TB 어댑터 — 명령 종류를 TB flags로, 결과를 결과 코드로 (03a P3a-9·P3a-10)")
class TigerBeetleLedgerClientTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    /** 컨테이너는 이 클래스만 쓰므로 고정된 양수에서 센다(System.nanoTime은 음수일 수 있다). */
    static final AtomicLong TRANSFER_IDS = new AtomicLong(1_000_000);
    static TigerBeetleTestLedger ledger;
    static com.tigerbeetle.Client adapterClient;
    static TigerBeetleLedgerClient adapter;

    Account payer;
    Account payee;

    @BeforeAll
    static void start() {
        TB.start();
        ledger = new TigerBeetleTestLedger(TB.newClient());
        adapterClient = TB.newClient();
        adapter = new TigerBeetleLedgerClient(adapterClient);
    }

    @AfterAll
    static void stop() {
        if (adapterClient != null) {
            adapterClient.close();
        }
        if (ledger != null) {
            ledger.close();
        }
        TB.stop();
    }

    @BeforeEach
    void givenTwoAccounts() {
        TB.assertAlive();
        payer = ledger.openAccount("구매자");
        payee = ledger.openAccount("가맹점");
    }

    @Nested
    @Order(1)
    @DisplayName("명령 종류마다 보내면")
    class EachKind {

        @Test
        @DisplayName("이체는 바로 확정된다")
        void aTransferIsPostedImmediately() {
            // when
            List<LedgerResultCode> results = submitted(transfer(500));

            // then
            assertThat(results).as("TB 결과").containsExactly(Created);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("예약은 금액을 보류하고 timeout을 함께 저장한다")
        void aReserveHoldsTheAmountWithItsTimeout() {
            // when
            Submission hold = reserve(500, 30);
            List<LedgerResultCode> results = submitted(hold);

            // then
            assertThat(results).as("TB 결과").containsExactly(Created);
            assertReserved(ledger, payer, payee, 500);
            assertThat(ledger.stored(hold.transferId()).timeoutSeconds()).as("저장된 timeout").isEqualTo(30);
        }

        @Test
        @DisplayName("금액 없는 확정은 예약의 계좌·ledger·code를 물려받아 전액을 확정한다")
        void aPostCapturesTheReservationInheritingItsFields() {
            // given
            Submission hold = reserve(500, 30);
            submitted(hold);

            // when
            List<LedgerResultCode> results = submitted(post(hold.transferId()));

            // then
            assertThat(results).as("TB 결과").containsExactly(Created);
            assertPostedAndReleased(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("해제는 예약 금액을 모두 푼다")
        void aVoidReleasesTheReservation() {
            // given
            Submission hold = reserve(500, 30);
            submitted(hold);

            // when
            List<LedgerResultCode> results = submitted(voidOf(hold.transferId()));

            // then
            assertThat(results).as("TB 결과").containsExactly(Created);
            assertNothingMoved(ledger, payer);
        }

        @Test
        @DisplayName("user_data_128에는 명령의 행 id가 저장된다 — TB에서 PG를 역참조한다 (§5.6)")
        void userData128CarriesTheRowId() {
            // when
            Submission payment = new Submission(nextTransferId(), BigInteger.valueOf(42), body(CommandKind.TRANSFER,
                    accountIdOf(payer), accountIdOf(payee), BigInteger.valueOf(1), 1, 1, 0, Optional.empty()));
            submitted(payment);

            // then
            assertThat(ledger.stored(payment.transferId()).userData128()).as("저장된 user_data_128")
                    .isEqualTo(BigInteger.valueOf(42));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("여러 이체를 한 요청으로 보내면")
    class OneRequest {

        @Test
        @DisplayName("결과는 요청 순서대로 온다")
        void resultsComeBackInRequestOrder() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("지갑");
            Submission fromWallet = new Submission(nextTransferId(), BigInteger.ONE, body(CommandKind.TRANSFER,
                    accountIdOf(wallet), accountIdOf(payee), BigInteger.valueOf(100), 1, 1, 0, Optional.empty()));

            // when
            List<LedgerResultCode> results = submitted(transfer(1), fromWallet, transfer(2));

            // then
            assertThat(results).as("TB 결과").containsExactly(Created, ExceedsCredits, Created);
        }

        @Test
        @DisplayName("같은 요청을 다시 보내면 이미 기록된 것으로 인정된다")
        void aResubmittedBatchIsAlreadyRecorded() {
            // given
            Submission first = transfer(1);
            Submission second = transfer(2);
            submitted(first, second);

            // when
            List<LedgerResultCode> results = submitted(first, second);

            // then
            assertThat(results).as("TB 결과").containsExactly(Exists, Exists);
            assertMovedExactly(ledger, payer, payee, 3);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("부호 없는 큰 값과 user_data를 보내면")
    class LargeValues {

        @Test
        @DisplayName("u32 최댓값(ledger, timeout)과 user_data_64·32 최댓값이 그대로 저장된다 — int로 자르지 않는다")
        void nonZeroUserDataAndTheLargestU32ValuesRoundTrip() {
            // given: ledger 2^32-1의 계좌 둘
            Account from = ledger.openAccount("큰 ledger의 구매자", -1);
            Account to = ledger.openAccount("큰 ledger의 가맹점", -1);
            Submission hold = new Submission(nextTransferId(), BigInteger.ONE, new TransferBody(CommandKind.RESERVE,
                    accountIdOf(from), accountIdOf(to), BigInteger.valueOf(1), 4_294_967_295L, 65_535,
                    4_294_967_295L, Optional.empty(), -1L, -1));

            // when
            List<LedgerResultCode> results = submitted(hold);

            // then
            assertThat(results).as("TB 결과").containsExactly(Created);
            StoredTransfer stored = ledger.stored(hold.transferId());
            assertThat(Integer.toUnsignedLong(stored.ledger())).as("저장된 ledger").isEqualTo(4_294_967_295L);
            assertThat(stored.timeoutSeconds()).as("저장된 timeout").isEqualTo(4_294_967_295L);
            assertThat(stored.code()).as("저장된 code").isEqualTo(65_535);
            assertThat(stored.userData64()).as("저장된 user_data_64").isEqualTo(-1L);
            assertThat(stored.userData32()).as("저장된 user_data_32").isEqualTo(-1);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("이체를 ID로 조회하면 — 대사 (03b)")
    class Lookup {

        @Test
        @DisplayName("저장된 이체를 계좌·금액과 함께, 종류는 flags에서 정해 돌려준다")
        void lookupReturnsStoredTransfersWithTheirKind() {
            // given
            Submission payment = transfer(500);
            Submission hold = reserve(500, 30);
            submitted(payment, hold);
            Submission capture = post(hold.transferId());
            Submission otherHold = reserve(300, 30);
            submitted(capture, otherHold);
            Submission release = voidOf(otherHold.transferId());
            submitted(release);

            // when
            Map<BigInteger, LedgerTransfer> found = lookedUp(payment, hold, capture, otherHold, release);

            // then
            assertThat(found.get(payment.transferId()).kind()).as("이체의 종류").isEqualTo(CommandKind.TRANSFER);
            assertThat(found.get(hold.transferId()).kind()).as("예약의 종류").isEqualTo(CommandKind.RESERVE);
            assertThat(found.get(capture.transferId()).kind()).as("확정의 종류").isEqualTo(CommandKind.POST);
            assertThat(found.get(otherHold.transferId()).kind()).as("둘째 예약의 종류").isEqualTo(CommandKind.RESERVE);
            assertThat(found.get(release.transferId()).kind()).as("해제의 종류").isEqualTo(CommandKind.VOID);
            assertThat(found.get(payment.transferId()).amount()).as("이체 금액").isEqualTo(BigInteger.valueOf(500));
            assertThat(found.get(payment.transferId()).debitAccountId()).as("이체의 출금 계좌")
                    .isEqualTo(accountIdOf(payer));
            assertThat(found.get(capture.transferId()).amount()).as("전액 확정된 금액").isEqualTo(BigInteger.valueOf(500));
        }

        @Test
        @DisplayName("TB에 없는 ID는 결과에서 빠진다")
        void lookupOmitsUnknownIds() {
            // given
            Submission payment = transfer(500);
            submitted(payment);
            BigInteger unknown = nextTransferId();

            // when
            Map<BigInteger, LedgerTransfer> found = lookedUpIds(List.of(payment.transferId(), unknown));

            // then
            assertThat(found).as("찾은 이체").containsOnlyKeys(payment.transferId());
        }

        private Map<BigInteger, LedgerTransfer> lookedUp(Submission... submissions) {
            return lookedUpIds(java.util.Arrays.stream(submissions).map(Submission::transferId).toList());
        }

        private Map<BigInteger, LedgerTransfer> lookedUpIds(List<BigInteger> ids) {
            try {
                return adapter.lookup(ids).get(30, TimeUnit.SECONDS).stream()
                        .collect(java.util.stream.Collectors.toMap(LedgerTransfer::transferId, t -> t));
            } catch (Exception e) {
                throw new AssertionError("TB 조회 응답을 받지 못했다", e);
            }
        }
    }

    @Nested
    @Order(4)
    @DisplayName("닫힌 클라이언트로 보내면")
    class ClosedClient {

        @Test
        @DisplayName("다시 써도 회복되지 않는 실패(LedgerUnavailableException)로 알린다 — 일시적 실패와 구분되게 (P2d-8)")
        void aClosedClientIsReportedAsUnavailable() {
            // given
            com.tigerbeetle.Client closed = TB.newClient();
            closed.close();
            TigerBeetleLedgerClient onClosed = new TigerBeetleLedgerClient(closed);

            // when
            Throwable failure = catchThrowable(() -> onClosed.submit(List.of(transfer(1))).get(30, TimeUnit.SECONDS));

            // then
            Throwable cause = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
            assertThat(cause).as("닫힌 클라이언트로 보낸 결과").isInstanceOf(LedgerUnavailableException.class);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private List<LedgerResultCode> submitted(Submission... batch) {
        try {
            return adapter.submit(List.of(batch)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("TB 응답을 받지 못했다", e);
        }
    }

    private Submission transfer(long amount) {
        return new Submission(nextTransferId(), BigInteger.ONE, body(CommandKind.TRANSFER, accountIdOf(payer),
                accountIdOf(payee), BigInteger.valueOf(amount), 1, 1, 0, Optional.empty()));
    }

    private Submission reserve(long amount, long timeoutSeconds) {
        return new Submission(nextTransferId(), BigInteger.ONE, body(CommandKind.RESERVE, accountIdOf(payer),
                accountIdOf(payee), BigInteger.valueOf(amount), 1, 1, timeoutSeconds, Optional.empty()));
    }

    /** 금액을 생략한 확정: 계좌·ledger·code 0, AMOUNT_MAX. core가 만드는 것과 같은 꼴이다. */
    private static Submission post(BigInteger reservationTransferId) {
        return new Submission(nextTransferId(), BigInteger.ONE, body(CommandKind.POST, BigInteger.ZERO,
                BigInteger.ZERO, LedgerCommand.AMOUNT_MAX, 0, 0, 0, Optional.of(reservationTransferId)));
    }

    private static Submission voidOf(BigInteger reservationTransferId) {
        return new Submission(nextTransferId(), BigInteger.ONE, body(CommandKind.VOID, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, 0, 0, 0, Optional.of(reservationTransferId)));
    }

    private static TransferBody body(CommandKind kind, BigInteger debit, BigInteger credit, BigInteger amount,
            long ledgerId, int code, long timeoutSeconds, Optional<BigInteger> pendingTransferId) {
        return new TransferBody(kind, debit, credit, amount, ledgerId, code, timeoutSeconds, pendingTransferId, 0, 0);
    }

    private static BigInteger accountIdOf(Account account) {
        return UInt128.asBigInteger(account.id());
    }

    private static BigInteger nextTransferId() {
        return BigInteger.valueOf(TRANSFER_IDS.incrementAndGet());
    }
}
