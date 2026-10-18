package io.github.ledgerrelay.tigerbeetle;

import static com.tigerbeetle.CreateTransferStatus.AccountsMustHaveTheSameLedger;
import static com.tigerbeetle.CreateTransferStatus.DebitAccountAlreadyClosed;
import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static com.tigerbeetle.CreateTransferStatus.ExceedsPendingTransferAmount;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferAlreadyPosted;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferExpired;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferHasDifferentAmount;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferHasDifferentCode;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferHasDifferentCreditAccountId;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferHasDifferentDebitAccountId;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferHasDifferentLedger;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferNotFound;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferNotPending;
import static com.tigerbeetle.CreateTransferStatus.TimeoutReservedForPendingTransfer;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertAlreadyRecorded;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedBecauseIdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertPostedAndReleased;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedFor;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertReserved;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.postOf;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.reservation;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.voidOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.StoredTransfer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import io.github.ledgerrelay.tigerbeetle.testing.Transfer;
import java.math.BigInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
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

/**
 * Sources: docs.tigerbeetle.com/reference/transfer (amount, pending_id, timeout, user_data),
 * docs.tigerbeetle.com/reference/requests/create_transfers, and the post/void handling (L4078-4250) and exists
 * comparison (L4337-4372) in src/state_machine.zig (tag 0.17.9). The post amount described in create_transfers#exists
 * differs from the source ("AMOUNT_MAX or equal to the stored amount", not "at least the reserved amount"). Decisions
 * rest on the source and on this test.
 */
@DisplayName("TigerBeetle 0.17.9 reservation, post and void rules — A2·A3, the basis")
class ReservationSemanticsTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    static TigerBeetleTestLedger ledger;

    Account payer;
    Account payee;
    Transfer hold;

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
    void givenA500Reservation() {
        TB.assertAlive();
        payer = ledger.openAccount("buyer");
        payee = ledger.openAccount("merchant");
        hold = ledger.recorded(reservation(500).from(payer).to(payee));
    }

    @Nested
    @Order(1)
    @DisplayName("When a reservation of 500 is posted without an amount")
    class FullPost {

        @Test
        @DisplayName("The full reserved amount is posted, and TB stores the actual amount 500, not AMOUNT_MAX")
        void aFullPostCapturesTheWholeReservation() {
            // when
            Transfer post = postOf(hold);
            CreateTransferStatus result = ledger.submit(post);

            // then
            assertCreated(result);
            assertPostedAndReleased(ledger, payer, payee, 500);
            assertThat(ledger.stored(post).amount()).as("post amount stored by TB").isEqualTo(BigInteger.valueOf(500));
        }

        @Test
        @DisplayName("Resending the same post is accepted as already recorded, and money moves only once (A3)")
        void resendingTheFullPostIsAlreadyRecorded() {
            // given
            Transfer post = ledger.recorded(postOf(hold));

            // when
            CreateTransferStatus result = ledger.submit(post);

            // then
            assertAlreadyRecorded(result);
            assertPostedAndReleased(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("Resending the same id with the reserved amount 500 written out is also accepted as already recorded")
        void resendingWithTheExplicitAmountIsAlreadyRecorded() {
            // given
            Transfer post = ledger.recorded(postOf(hold));

            // when
            CreateTransferStatus result = ledger.submit(post.withAmount(500));

            // then
            assertAlreadyRecorded(result);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a reservation of 500 is posted partially or with 0")
    class PartialPost {

        @Test
        @DisplayName("Posting only 300 posts 300 and releases the remaining 200")
        void aPartialPostReleasesTheRest() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold).withAmount(300));

            // then
            assertCreated(result);
            assertPostedAndReleased(ledger, payer, payee, 300);
        }

        @Test
        @DisplayName("Posting 0 is a zero-amount post, not a full post, and releases the whole reserved amount (the case core blocks)")
        void aZeroPostCapturesNothingAndReleasesAll() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold).withAmount(0));

            // then
            assertCreated(result);
            assertPostedAndReleased(ledger, payer, payee, 0);
        }

        @Test
        @DisplayName("Posting 501, more than the reservation, is rejected, and resending the same id gives the same reason — the id is not used up")
        void aPostAboveTheReservationIsRejectedAndTheIdStaysUsable() {
            // given
            Transfer tooLarge = postOf(hold).withAmount(501);

            // when
            CreateTransferStatus first = ledger.submit(tooLarge);
            CreateTransferStatus again = ledger.submit(tooLarge);

            // then
            assertRejectedFor(first, ExceedsPendingTransferAmount);
            assertRejectedFor(again, ExceedsPendingTransferAmount);
            assertReserved(ledger, payer, payee, 500);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When a reservation of 500 is voided")
    class Void {

        @Test
        @DisplayName("The whole reserved amount is released, and nothing is posted")
        void aVoidReleasesTheWholeReservation() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold));

            // then
            assertCreated(result);
            assertNothingMoved(ledger, payer);
        }

        @Test
        @DisplayName("Resending the same void is accepted as already recorded (A3)")
        void voidResentIsAlreadyRecorded() {
            // given
            Transfer release = ledger.recorded(voidOf(hold));

            // when
            CreateTransferStatus result = ledger.submit(release);

            // then
            assertAlreadyRecorded(result);
        }

        @Test
        @DisplayName("A void with an amount smaller than the reservation is rejected because the amount differs")
        void aVoidWithASmallerAmountIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold).withAmount(300));

            // then
            assertRejectedFor(result, PendingTransferHasDifferentAmount);
        }

        @Test
        @DisplayName("A void with an amount larger than the reservation is rejected for exceeding the reserved amount")
        void aVoidWithALargerAmountIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold).withAmount(501));

            // then
            assertRejectedFor(result, ExceedsPendingTransferAmount);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When a post or void leaves the reservation's values empty")
    class Inheritance {

        Transfer tagged;

        @BeforeEach
        void givenAReservationWithUserData() {
            tagged = ledger.recorded(reservation(500).from(payer).to(payee).withUserData64(7).withUserData32(9));
        }

        @Test
        @DisplayName("A post is stored with the reservation's accounts, ledger and code")
        void aPostInheritsAccountsLedgerAndCode() {
            // when
            Transfer post = ledger.recorded(postOf(tagged));

            // then
            StoredTransfer stored = ledger.stored(post);
            assertThat(stored.debitAccountId()).as("debit account of the post").isEqualTo(payer.id());
            assertThat(stored.creditAccountId()).as("credit account of the post").isEqualTo(payee.id());
            assertThat(stored.ledger()).as("ledger of the post").isEqualTo(1);
            assertThat(stored.code()).as("code of the post").isEqualTo(1);
        }

        @Test
        @DisplayName("A post fills user_data_64 and user_data_32 sent as 0 with the reservation's values (behavior core did not assume)")
        void aPostInheritsUserData() {
            // when
            Transfer post = ledger.recorded(postOf(tagged));

            // then
            StoredTransfer stored = ledger.stored(post);
            assertThat(stored.userData64()).as("user_data_64 of the post").isEqualTo(7);
            assertThat(stored.userData32()).as("user_data_32 of the post").isEqualTo(9);
        }

        @Test
        @DisplayName("A void also fills user_data_64 and user_data_32 with the reservation's values")
        void aVoidInheritsUserData() {
            // when
            Transfer release = ledger.recorded(voidOf(tagged));

            // then
            StoredTransfer stored = ledger.stored(release);
            assertThat(stored.userData64()).as("user_data_64 of the void").isEqualTo(7);
            assertThat(stored.userData32()).as("user_data_32 of the void").isEqualTo(9);
        }

        @Test
        @DisplayName("Resending a post that inherited user_data with the same body is accepted as already recorded")
        void resendingAPostThatInheritedUserDataIsAlreadyRecorded() {
            // given
            Transfer post = ledger.recorded(postOf(tagged));

            // when
            CreateTransferStatus result = ledger.submit(post);

            // then
            assertAlreadyRecorded(result);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("When a post states values different from the reservation")
    class DifferentFromTheReservation {

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("differences")
        @DisplayName("It is rejected because that field differs, and the reservation stays held")
        void isRejectedAndTheReservationStays(BiFunction<Transfer, Account, Transfer> change,
                CreateTransferStatus reason) {
            // given
            Account other = ledger.openAccount("other account");

            // when
            CreateTransferStatus result = ledger.submit(change.apply(postOf(hold), other));

            // then
            assertRejectedFor(result, reason);
            assertReserved(ledger, payer, payee, 500);
        }

        static Stream<Arguments> differences() {
            return Stream.of(
                    argumentSet("changing the debit account → PendingTransferHasDifferentDebitAccountId",
                            change((post, other) -> post.withDebitAccount(other)),
                            PendingTransferHasDifferentDebitAccountId),
                    argumentSet("changing the credit account → PendingTransferHasDifferentCreditAccountId",
                            change((post, other) -> post.withCreditAccount(other)),
                            PendingTransferHasDifferentCreditAccountId),
                    argumentSet("stating ledger 2 → PendingTransferHasDifferentLedger",
                            change((post, other) -> post.withLedger(2)), PendingTransferHasDifferentLedger),
                    argumentSet("stating code 2 → PendingTransferHasDifferentCode",
                            change((post, other) -> post.withCode(2)), PendingTransferHasDifferentCode));
        }

        private static BiFunction<Transfer, Account, Transfer> change(BiFunction<Transfer, Account, Transfer> change) {
            return change;
        }
    }

    @Nested
    @Order(6)
    @DisplayName("When a finished reservation is posted or voided again")
    class FinishedReservations {

        @Test
        @DisplayName("Resending the same post to a posted reservation is accepted as already recorded, not as 'already posted' — the exists check comes first")
        void postingAPostedReservationAgainWithTheSamePostIsAlreadyRecorded() {
            // given
            Transfer post = ledger.recorded(postOf(hold));

            // when
            CreateTransferStatus result = ledger.submit(post);

            // then
            assertAlreadyRecorded(result);
        }

        @Test
        @DisplayName("Voiding a posted reservation is rejected as 'already posted', and resending the same id gives the same reason")
        void voidingAPostedReservationIsRejectedEveryTime() {
            // given
            ledger.recorded(postOf(hold));
            Transfer release = voidOf(hold);

            // when
            CreateTransferStatus first = ledger.submit(release);
            CreateTransferStatus again = ledger.submit(release);

            // then
            assertRejectedFor(first, PendingTransferAlreadyPosted);
            assertRejectedFor(again, PendingTransferAlreadyPosted);
        }

        @Test
        @DisplayName("Posting an expired reservation is rejected as expired, and resending the same id gives the same reason")
        void postingAnExpiredReservationIsRejectedEveryTime() {
            // given: a 1-second reservation has expired by the TB clock
            Account buyer = ledger.openAccount("buyer of the reservation that expires");
            Transfer shortHold = ledger.recorded(reservation(500).from(buyer).to(payee).withTimeout(1));
            ledger.waitUntilExpired(shortHold);
            Transfer post = postOf(shortHold);

            // when
            CreateTransferStatus first = ledger.submit(post);
            CreateTransferStatus again = ledger.submit(post);

            // then
            assertRejectedFor(first, PendingTransferExpired);
            assertRejectedFor(again, PendingTransferExpired);
        }

        /**
         * The expiry time is the timestamp at which TB applied the reservation plus the timeout
         * (state_machine.zig L3978, {@code expires_at = timestamp_actual + timeout_ns}). It is measured by the TB cluster
         * clock, not by PG {@code now()}.
         * The test cannot choose when TB applies the reservation, so it checks only the stored timeout.
         */
        @Test
        @DisplayName("A reservation stores the timeout (seconds) it received unchanged — expiry is measured by the TB cluster clock")
        void aReservationKeepsItsTimeout() {
            // when
            Transfer shortHold = ledger.recorded(reservation(1).from(ledger.openAccount("buyer")).to(payee)
                    .withTimeout(1));

            // then
            assertThat(ledger.stored(shortHold).timeoutSeconds()).as("stored timeout (seconds)").isEqualTo(1);
        }
    }

    @Nested
    @Order(7)
    @DisplayName("When an id whose post failed is used again")
    class UsedUpIds {

        @Test
        @DisplayName("A post that refers to a reservation not in the ledger is rejected as reservation not found, and the id is used up: resending it gives id already failed")
        void aPostOfAnUnknownReservationUsesUpItsId() {
            // given
            Transfer post = postOf(reservation(500).from(payer).to(payee));

            // when
            CreateTransferStatus first = ledger.submit(post);
            CreateTransferStatus again = ledger.submit(post);

            // then
            assertRejectedFor(first, PendingTransferNotFound);
            assertRejectedBecauseIdAlreadyFailed(again);
        }

        @Test
        @DisplayName("A post that refers to a reservation that failed for insufficient funds is rejected as reservation not found")
        void aPostOfAFailedReservationFindsNoReservation() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("wallet");
            Transfer failedHold = ledger.rejected(reservation(500).from(wallet).to(payee), ExceedsCredits);

            // when
            CreateTransferStatus result = ledger.submit(postOf(failedHold));

            // then
            assertRejectedFor(result, PendingTransferNotFound);
        }

        @Test
        @DisplayName("Resending a used-up id with a changed body is rejected as id already failed, not as a content conflict")
        void aUsedUpIdIgnoresAChangedBody() {
            // given
            Transfer post = ledger.rejected(postOf(reservation(500).from(payer).to(payee)), PendingTransferNotFound);

            // when
            CreateTransferStatus result = ledger.submit(post.withAmount(1));

            // then
            assertRejectedBecauseIdAlreadyFailed(result);
        }

        @Test
        @DisplayName("An id rejected for a non-transient reason is not used up, so resending it is decided again with the same reason (A2)")
        void aNonTransientRejectionIsJudgedAgain() {
            // given
            Account elsewhere = ledger.openAccount("merchant on another ledger", 2);
            Transfer payment = transfer(100).from(payer).to(elsewhere);
            CreateTransferStatus first = ledger.submit(payment);

            // when
            CreateTransferStatus again = ledger.submit(payment);

            // then
            assertRejectedFor(first, AccountsMustHaveTheSameLedger);
            assertRejectedFor(again, AccountsMustHaveTheSameLedger);
        }
    }

    @Nested
    @Order(8)
    @DisplayName("When the account holding a reservation is closed")
    class ClosedAccounts {

        @BeforeEach
        void givenThePayerIsClosed() {
            ledger.closeAccount(payer);
        }

        @Test
        @DisplayName("A post is rejected because the debit account is closed")
        void aPostOnAClosedAccountIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold));

            // then
            assertRejectedFor(result, DebitAccountAlreadyClosed);
        }

        @Test
        @DisplayName("A void is allowed — the only movement a closed account allows")
        void aVoidOnAClosedAccountIsAllowed() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold));

            // then
            assertCreated(result);
        }
    }

    @Nested
    @Order(9)
    @DisplayName("When a post or transfer that breaks the rules is sent")
    class InvalidRequests {

        @Test
        @DisplayName("A post that refers to a transfer that is not a reservation is rejected because it is not a reservation")
        void aPostOfAPlainTransferIsRejected() {
            // given
            Transfer payment = ledger.recorded(transfer(100).from(payer).to(payee));

            // when
            CreateTransferStatus result = ledger.submit(postOf(payment));

            // then
            assertRejectedFor(result, PendingTransferNotPending);
        }

        @Test
        @DisplayName("A timeout on a transfer that is not a reservation is rejected")
        void aTimeoutOnAPlainTransferIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(transfer(100).from(payer).to(payee).withTimeout(30));

            // then
            assertRejectedFor(result, TimeoutReservedForPendingTransfer);
        }

        @Test
        @DisplayName("A timeout on a post is rejected")
        void aTimeoutOnAPostIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold).withTimeout(30));

            // then
            assertRejectedFor(result, TimeoutReservedForPendingTransfer);
        }

        @Test
        @DisplayName("A zero-amount transfer is recorded (since 0.16.0)")
        void aZeroTransferIsRecorded() {
            // when
            CreateTransferStatus result = ledger.submit(transfer(0).from(payer).to(payee));

            // then
            assertCreated(result);
        }

        @Test
        @DisplayName("A zero-amount reservation is recorded too")
        void aZeroReservationIsRecorded() {
            // when
            CreateTransferStatus result = ledger.submit(reservation(0).from(payer).to(payee));

            // then
            assertCreated(result);
        }
    }
}
