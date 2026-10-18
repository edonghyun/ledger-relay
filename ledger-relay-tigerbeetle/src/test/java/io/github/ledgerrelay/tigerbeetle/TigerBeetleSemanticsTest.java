package io.github.ledgerrelay.tigerbeetle;

import static com.tigerbeetle.CreateTransferStatus.DebitAccountNotFound;
import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentCode;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentCreditAccountId;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentDebitAccountId;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentLedger;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentUserData128;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentUserData32;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentUserData64;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertAlreadyRecorded;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertConflictOnAmount;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertContentConflict;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedBecauseIdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedFor;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import io.github.ledgerrelay.tigerbeetle.testing.Transfer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** Source: docs.tigerbeetle.com/reference/requests/create_transfers (exists, exists_with_different_*, id_already_failed). */
@DisplayName("TigerBeetle 0.17.9 result rules — the basis of the classification table")
class TigerBeetleSemanticsTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    static TigerBeetleTestLedger ledger;

    @BeforeAll
    static void start() {
        TB.start();
        ledger = new TigerBeetleTestLedger(TB.newClient());
    }

    @AfterAll
    static void stop() {
        if (ledger != null) {
            ledger.close();
        }
        TB.stop();
    }

    @BeforeEach
    void serverIsAlive() {
        TB.assertAlive();
    }

    @Nested
    @Order(1)
    @DisplayName("After a payment of 500 is recorded")
    class AfterAPaymentIsRecorded {

        Account payer;
        Account payee;
        Transfer payment;

        @BeforeEach
        void givenARecordedPayment() {
            payer = ledger.openAccount("buyer");
            payee = ledger.openAccount("merchant");
            payment = ledger.recorded(transfer(500).from(payer).to(payee));
        }

        @Test
        @DisplayName("Resending the same payment is accepted as already recorded and moves money only once")
        void resendingTheSamePayment() {
            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertAlreadyRecorded(result);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("Resending the same id with only the amount changed is rejected as a content conflict, and only the first amount remains (G7)")
        void resendingWithADifferentAmount() {
            // when
            CreateTransferStatus result = ledger.submit(payment.withAmount(501));

            // then
            assertConflictOnAmount(result);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("contentChanges")
        @DisplayName("Resending the same id with content other than the amount changed is rejected as a conflict on the changed field, and only the first transfer remains (G7)")
        void resendingWithOtherContentChanged(UnaryOperator<Transfer> change, CreateTransferStatus conflict) {
            // when
            CreateTransferStatus result = ledger.submit(change.apply(payment));

            // then
            assertContentConflict(result, conflict);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        static Stream<Arguments> contentChanges() {
            return Stream.of(
                    argumentSet("changing the debit account → ExistsWithDifferentDebitAccountId",
                            change(t -> t.withDebitAccount(ledger.openAccount("another buyer"))),
                            ExistsWithDifferentDebitAccountId),
                    argumentSet("changing the credit account → ExistsWithDifferentCreditAccountId",
                            change(t -> t.withCreditAccount(ledger.openAccount("another merchant"))),
                            ExistsWithDifferentCreditAccountId),
                    argumentSet("changing ledger → ExistsWithDifferentLedger",
                            change(t -> t.withLedger(2)), ExistsWithDifferentLedger),
                    argumentSet("changing code → ExistsWithDifferentCode",
                            change(t -> t.withCode(2)), ExistsWithDifferentCode),
                    argumentSet("changing user_data_128 (the module's back-reference to the command) → ExistsWithDifferentUserData128",
                            change(t -> t.withUserData128(42)), ExistsWithDifferentUserData128),
                    argumentSet("changing user_data_64 → ExistsWithDifferentUserData64",
                            change(t -> t.withUserData64(42)), ExistsWithDifferentUserData64),
                    argumentSet("changing user_data_32 → ExistsWithDifferentUserData32",
                            change(t -> t.withUserData32(42)), ExistsWithDifferentUserData32));
        }

        private static UnaryOperator<Transfer> change(UnaryOperator<Transfer> change) {
            return change;
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When paying from a wallet with insufficient balance")
    class PayingFromAnEmptyWallet {

        Account wallet;
        Account merchant;
        Transfer payment;

        @BeforeEach
        void givenAnEmptyWallet() {
            wallet = ledger.openAccountThatCannotOverdraw("wallet");
            merchant = ledger.openAccount("merchant");
            payment = transfer(100).from(wallet).to(merchant);
        }

        @Test
        @DisplayName("The payment is rejected for insufficient funds, and no money moves")
        void isRejectedForInsufficientFunds() {
            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertRejectedFor(result, ExceedsCredits);
            assertNothingMoved(ledger, wallet);
        }

        @Test
        @DisplayName("Resending the same payment is rejected because this id already failed")
        void resendingTheFailedPayment() {
            // given
            ledger.rejected(payment, ExceedsCredits);

            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertRejectedBecauseIdAlreadyFailed(result);
        }

        @Test
        @DisplayName("After a top-up the same id stays failed, and only a new id pays (a retry is a new command)")
        void onlyANewIdSucceedsAfterTopUp() {
            // given: the payment failed once, and then 100 was deposited
            ledger.rejected(payment, ExceedsCredits);
            ledger.deposit(wallet, 100);

            // when
            CreateTransferStatus sameId = ledger.submit(payment);
            CreateTransferStatus newId = ledger.submit(payment.withNewId());

            // then
            assertRejectedBecauseIdAlreadyFailed(sameId);
            assertCreated(newId);
            assertMovedExactly(ledger, wallet, merchant, 100);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When debiting an account that does not exist")
    class PayingFromAnUnknownAccount {

        Account unknown;
        Account merchant;
        Transfer payment;

        @BeforeEach
        void givenAnAccountNotYetOpened() {
            unknown = Account.notOpened("account not in the ledger");
            merchant = ledger.openAccount("merchant");
            payment = transfer(1).from(unknown).to(merchant);
        }

        @Test
        @DisplayName("The payment is rejected as an invalid request")
        void isRejectedAsUnknownAccount() {
            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertRejectedFor(result, DebitAccountNotFound);
        }

        @Test
        @DisplayName("After that account is opened the same id stays failed, and only a new id pays")
        void onlyANewIdSucceedsAfterOpeningTheAccount() {
            // given: a payment from the missing account failed, and then that account was opened
            ledger.rejected(payment, DebitAccountNotFound);
            ledger.open(unknown);

            // when
            CreateTransferStatus sameId = ledger.submit(payment);
            CreateTransferStatus newId = ledger.submit(payment.withNewId());

            // then
            assertRejectedBecauseIdAlreadyFailed(sameId);
            assertCreated(newId);
            assertMovedExactly(ledger, unknown, merchant, 1);
        }
    }
}
