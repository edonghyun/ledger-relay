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

@DisplayName("TB adapter — command kinds to TB flags, results to result codes")
class TigerBeetleLedgerClientTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    /** Only this class uses the container, so ids count up from a fixed positive number (nanoTime can be negative). */
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
        payer = ledger.openAccount("buyer");
        payee = ledger.openAccount("merchant");
    }

    @Nested
    @Order(1)
    @DisplayName("When each command kind is sent")
    class EachKind {

        @Test
        @DisplayName("A transfer is posted immediately")
        void aTransferIsPostedImmediately() {
            // when
            List<LedgerResultCode> results = submitted(transfer(500));

            // then
            assertThat(results).as("TB results").containsExactly(Created);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("A reservation holds the amount and stores its timeout with it")
        void aReserveHoldsTheAmountWithItsTimeout() {
            // when
            Submission hold = reserve(500, 30);
            List<LedgerResultCode> results = submitted(hold);

            // then
            assertThat(results).as("TB results").containsExactly(Created);
            assertReserved(ledger, payer, payee, 500);
            assertThat(ledger.stored(hold.transferId()).timeoutSeconds()).as("stored timeout").isEqualTo(30);
        }

        @Test
        @DisplayName("A post without an amount inherits the reservation's accounts, ledger and code and posts the full amount")
        void aPostCapturesTheReservationInheritingItsFields() {
            // given
            Submission hold = reserve(500, 30);
            submitted(hold);

            // when
            List<LedgerResultCode> results = submitted(post(hold.transferId()));

            // then
            assertThat(results).as("TB results").containsExactly(Created);
            assertPostedAndReleased(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("A void releases the whole reserved amount")
        void aVoidReleasesTheReservation() {
            // given
            Submission hold = reserve(500, 30);
            submitted(hold);

            // when
            List<LedgerResultCode> results = submitted(voidOf(hold.transferId()));

            // then
            assertThat(results).as("TB results").containsExactly(Created);
            assertNothingMoved(ledger, payer);
        }

        @Test
        @DisplayName("user_data_128 stores the command's row id — a back-reference from TB to PG")
        void userData128CarriesTheRowId() {
            // when
            Submission payment = new Submission(nextTransferId(), BigInteger.valueOf(42), body(CommandKind.TRANSFER,
                    accountIdOf(payer), accountIdOf(payee), BigInteger.valueOf(1), 1, 1, 0, Optional.empty()));
            submitted(payment);

            // then
            assertThat(ledger.stored(payment.transferId()).userData128()).as("stored user_data_128")
                    .isEqualTo(BigInteger.valueOf(42));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When several transfers are sent in one request")
    class OneRequest {

        @Test
        @DisplayName("Results come back in request order")
        void resultsComeBackInRequestOrder() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("wallet");
            Submission fromWallet = new Submission(nextTransferId(), BigInteger.ONE, body(CommandKind.TRANSFER,
                    accountIdOf(wallet), accountIdOf(payee), BigInteger.valueOf(100), 1, 1, 0, Optional.empty()));

            // when
            List<LedgerResultCode> results = submitted(transfer(1), fromWallet, transfer(2));

            // then
            assertThat(results).as("TB results").containsExactly(Created, ExceedsCredits, Created);
        }

        @Test
        @DisplayName("Resending the same request is accepted as already recorded")
        void aResubmittedBatchIsAlreadyRecorded() {
            // given
            Submission first = transfer(1);
            Submission second = transfer(2);
            submitted(first, second);

            // when
            List<LedgerResultCode> results = submitted(first, second);

            // then
            assertThat(results).as("TB results").containsExactly(Exists, Exists);
            assertMovedExactly(ledger, payer, payee, 3);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When large unsigned values and user_data are sent")
    class LargeValues {

        @Test
        @DisplayName("The u32 maximum (ledger, timeout) and the user_data_64 and user_data_32 maximums are stored unchanged — not truncated to int")
        void nonZeroUserDataAndTheLargestU32ValuesRoundTrip() {
            // given: two accounts on ledger 2^32-1
            Account from = ledger.openAccount("buyer on the large ledger", -1);
            Account to = ledger.openAccount("merchant on the large ledger", -1);
            Submission hold = new Submission(nextTransferId(), BigInteger.ONE, new TransferBody(CommandKind.RESERVE,
                    accountIdOf(from), accountIdOf(to), BigInteger.valueOf(1), 4_294_967_295L, 65_535,
                    4_294_967_295L, Optional.empty(), -1L, -1));

            // when
            List<LedgerResultCode> results = submitted(hold);

            // then
            assertThat(results).as("TB results").containsExactly(Created);
            StoredTransfer stored = ledger.stored(hold.transferId());
            assertThat(Integer.toUnsignedLong(stored.ledger())).as("stored ledger").isEqualTo(4_294_967_295L);
            assertThat(stored.timeoutSeconds()).as("stored timeout").isEqualTo(4_294_967_295L);
            assertThat(stored.code()).as("stored code").isEqualTo(65_535);
            assertThat(stored.userData64()).as("stored user_data_64").isEqualTo(-1L);
            assertThat(stored.userData32()).as("stored user_data_32").isEqualTo(-1);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("When transfers are looked up by id — reconciliation")
    class Lookup {

        @Test
        @DisplayName("Stored transfers come back with accounts and amount, and the kind is derived from the flags")
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
            assertThat(found.get(payment.transferId()).kind()).as("kind of the transfer").isEqualTo(CommandKind.TRANSFER);
            assertThat(found.get(hold.transferId()).kind()).as("kind of the reservation").isEqualTo(CommandKind.RESERVE);
            assertThat(found.get(capture.transferId()).kind()).as("kind of the post").isEqualTo(CommandKind.POST);
            assertThat(found.get(otherHold.transferId()).kind()).as("kind of the second reservation").isEqualTo(CommandKind.RESERVE);
            assertThat(found.get(release.transferId()).kind()).as("kind of the void").isEqualTo(CommandKind.VOID);
            assertThat(found.get(payment.transferId()).amount()).as("transfer amount").isEqualTo(BigInteger.valueOf(500));
            assertThat(found.get(payment.transferId()).debitAccountId()).as("debit account of the transfer")
                    .isEqualTo(accountIdOf(payer));
            assertThat(found.get(capture.transferId()).amount()).as("amount of the full-amount post").isEqualTo(BigInteger.valueOf(500));
        }

        @Test
        @DisplayName("Ids that TB does not have are left out of the result")
        void lookupOmitsUnknownIds() {
            // given
            Submission payment = transfer(500);
            submitted(payment);
            BigInteger unknown = nextTransferId();

            // when
            Map<BigInteger, LedgerTransfer> found = lookedUpIds(List.of(payment.transferId(), unknown));

            // then
            assertThat(found).as("transfers found").containsOnlyKeys(payment.transferId());
        }

        private Map<BigInteger, LedgerTransfer> lookedUp(Submission... submissions) {
            return lookedUpIds(java.util.Arrays.stream(submissions).map(Submission::transferId).toList());
        }

        private Map<BigInteger, LedgerTransfer> lookedUpIds(List<BigInteger> ids) {
            try {
                return adapter.lookup(ids).get(30, TimeUnit.SECONDS).stream()
                        .collect(java.util.stream.Collectors.toMap(LedgerTransfer::transferId, t -> t));
            } catch (Exception e) {
                throw new AssertionError("No reply to the TB lookup", e);
            }
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When sent through a closed client")
    class ClosedClient {

        @Test
        @DisplayName("It is reported as a failure that retrying does not fix (LedgerUnavailableException) — distinct from transient failures")
        void aClosedClientIsReportedAsUnavailable() {
            // given
            com.tigerbeetle.Client closed = TB.newClient();
            closed.close();
            TigerBeetleLedgerClient onClosed = new TigerBeetleLedgerClient(closed);

            // when
            Throwable failure = catchThrowable(() -> onClosed.submit(List.of(transfer(1))).get(30, TimeUnit.SECONDS));

            // then
            Throwable cause = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
            assertThat(cause).as("result of sending through a closed client").isInstanceOf(LedgerUnavailableException.class);
        }
    }

    // ── Vocabulary used only in this file ──────────────────────────────────────────

    private List<LedgerResultCode> submitted(Submission... batch) {
        try {
            return adapter.submit(List.of(batch)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("No reply from TB", e);
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

    /** A post with the amount left out: accounts, ledger and code 0, AMOUNT_MAX. The same shape core builds. */
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
