package io.github.ledgerrelay.tigerbeetle.testing;

import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertPostedAndReleased;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertReserved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertStillWaitingAfter;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.reservation;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Ledger vocabulary — docs/testing.md §3·§5: do preconditions and effect checks stop correctly when the situation is wrong")
class TigerBeetleTestLedgerTest {

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
    @DisplayName("A given precondition method")
    class Preconditions {

        @Test
        @DisplayName("recorded: building a 'recorded' precondition from a transfer that is actually rejected stops at setup")
        void recordedStopsWhenTheTransferIsRejected() {
            Account wallet = ledger.openAccountThatCannotOverdraw("wallet");
            Account merchant = ledger.openAccount("merchant");

            assertThatThrownBy(() -> ledger.recorded(transfer(100).from(wallet).to(merchant)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given setup failed")
                    .hasMessageContaining("ExceedsCredits");
        }

        @Test
        @DisplayName("rejected: building a 'rejected' precondition from a transfer that is actually recorded stops at setup")
        void rejectedStopsWhenTheTransferIsCreated() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");

            assertThatThrownBy(() -> ledger.rejected(transfer(100).from(payer).to(payee), ExceedsCredits))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given setup failed")
                    .hasMessageContaining("Created");
        }

        @Test
        @DisplayName("closeAccount: closing an already closed account again stops at setup")
        void closeAccountRefusesAnAlreadyClosedAccount() {
            Account payer = ledger.openAccount("account to close");
            ledger.closeAccount(payer);

            assertThatThrownBy(() -> ledger.closeAccount(payer))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given setup failed")
                    .hasMessageContaining("account to close");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a reservation is sent")
    class Reservations {

        @Test
        @DisplayName("reservation: the amount is only held, not posted")
        void reservingHoldsTheAmountWithoutPosting() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");

            ledger.recorded(reservation(500).from(payer).to(payee));

            Balance balance = ledger.balanceOf(payer);
            assertThat(balance.debitsPosted()).as("buyer's posted debits").isZero();
            assertThat(balance.debitsPending()).as("buyer's pending debits").isEqualTo(500);
        }

        @Test
        @DisplayName("stored: reads the values TB stored")
        void storedReadsWhatTigerBeetleKept() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            Transfer payment = ledger.recorded(transfer(500).from(payer).to(payee));

            StoredTransfer stored = ledger.stored(payment);

            assertThat(stored.amount()).as("stored amount").isEqualTo(BigInteger.valueOf(500));
            assertThat(stored.ledger()).as("stored ledger").isEqualTo(1);
            assertThat(stored.userData128()).as("stored user_data_128").isEqualTo(BigInteger.ZERO);
        }

        @Test
        @DisplayName("stored: reading a transfer that is not in the ledger fails")
        void storedFailsForAnUnknownTransfer() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            Transfer neverSent = transfer(500).from(payer).to(payee);

            assertThatThrownBy(() -> ledger.stored(neverSent))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not in the ledger");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("An account opened later")
    class AccountsOpenedLater {

        @Test
        @DisplayName("open: before opening it is not in the ledger; after opening it is found under the same id")
        void openUsesTheSameId() {
            Account later = Account.notOpened("account to open later");
            assertThatThrownBy(() -> ledger.balanceOf(later))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not in the ledger");

            ledger.open(later);

            assertThat(ledger.balanceOf(later).isUntouched()).isTrue();
        }

        @Test
        @DisplayName("openAccount(name, ledger): opens an account on that ledger — it cannot transfer to an account on another ledger")
        void openAccountOnAnotherLedger() {
            Account payer = ledger.openAccount("buyer");
            Account elsewhere = ledger.openAccount("merchant on another ledger", 2);

            assertThatThrownBy(() -> ledger.recorded(transfer(1).from(payer).to(elsewhere)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given setup failed")
                    .hasMessageContaining("Ledger");
        }

        @Test
        @DisplayName("open: opening an already opened account again stops at setup")
        void openStopsWhenTheAccountAlreadyExists() {
            Account opened = ledger.openAccount("account already opened");

            assertThatThrownBy(() -> ledger.open(opened))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given setup failed")
                    .hasMessageContaining("account already opened");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("An assert that checks an effect")
    class EffectAssertions {

        @Test
        @DisplayName("assertMovedExactly: when the amount actually moved differs, fails with the expected amount and the actual balances")
        void movedExactlyFailsOnADifferentAmount() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            ledger.recorded(transfer(500).from(payer).to(payee));

            assertThatThrownBy(() -> assertMovedExactly(ledger, payer, payee, 501))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("exactly 501")
                    .hasMessageContaining("debits 500");
        }

        @Test
        @DisplayName("assertNothingMoved: fails on an account where money moved")
        void nothingMovedFailsAfterAMove() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            ledger.recorded(transfer(1).from(payer).to(payee));

            assertThatThrownBy(() -> assertNothingMoved(ledger, payee))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("merchant")
                    .hasMessageContaining("credits 1");
        }

        @Test
        @DisplayName("assertStillWaitingAfter: fails when the request already got a reply")
        void stillWaitingFailsWhenTheReplyArrived() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            PendingTransfer pending = ledger.submitInBackground(transfer(1).from(payer).to(payee));
            pending.awaitResult(Duration.ofSeconds(30));

            assertThatThrownBy(() -> assertStillWaitingAfter(pending, Duration.ofMillis(100)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("reply");
        }

        @Test
        @DisplayName("assertReserved: fails when the amount was posted rather than held")
        void assertReservedFailsWhenTheAmountWasPosted() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            ledger.recorded(transfer(500).from(payer).to(payee));

            assertThatThrownBy(() -> assertReserved(ledger, payer, payee, 500))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("held")
                    .hasMessageContaining("debits 500");
        }

        @Test
        @DisplayName("assertPostedAndReleased: fails while an amount is still held")
        void assertPostedAndReleasedFailsWhileStillReserved() {
            Account payer = ledger.openAccount("buyer");
            Account payee = ledger.openAccount("merchant");
            ledger.recorded(reservation(500).from(payer).to(payee));

            assertThatThrownBy(() -> assertPostedAndReleased(ledger, payer, payee, 0))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("pending debits 500");
        }
    }
}
