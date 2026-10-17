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
 * 근거: docs.tigerbeetle.com/reference/transfer (amount, pending_id, timeout, user_data),
 * docs.tigerbeetle.com/reference/requests/create_transfers, 소스 src/state_machine.zig(tag 0.17.9)의 post·void 처리
 * (L4078-4250)와 exists 비교(L4337-4372). 문서 create_transfers#exists의 post 금액 설명은 소스와 다르다
 * ("예약 금액 이상"이 아니라 "AMOUNT_MAX이거나 저장된 금액과 같음"). 판단 근거는 소스와 이 테스트로 삼는다.
 */
@DisplayName("TigerBeetle 0.17.9 예약·확정·해제 규칙 — 스펙 §3.4 A2·A3, §4.4, P1-3의 근거")
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
        payer = ledger.openAccount("구매자");
        payee = ledger.openAccount("가맹점");
        hold = ledger.recorded(reservation(500).from(payer).to(payee));
    }

    @Nested
    @Order(1)
    @DisplayName("500원 예약을 금액 없이 확정하면")
    class FullPost {

        @Test
        @DisplayName("예약 금액 전부가 확정되고, TB에는 AMOUNT_MAX가 아닌 실제 금액 500이 저장된다")
        void aFullPostCapturesTheWholeReservation() {
            // when
            Transfer post = postOf(hold);
            CreateTransferStatus result = ledger.submit(post);

            // then
            assertCreated(result);
            assertPostedAndReleased(ledger, payer, payee, 500);
            assertThat(ledger.stored(post).amount()).as("TB가 저장한 확정 금액").isEqualTo(BigInteger.valueOf(500));
        }

        @Test
        @DisplayName("같은 확정을 다시 보내면 이미 기록된 것으로 인정되고, 돈은 한 번만 움직인다 (A3)")
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
        @DisplayName("같은 ID에 예약 금액 500을 적어 다시 보내도 이미 기록된 것으로 인정된다")
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
    @DisplayName("500원 예약을 일부나 0원으로 확정하면")
    class PartialPost {

        @Test
        @DisplayName("300원만 확정하면 300원이 확정되고 나머지 200원은 풀린다")
        void aPartialPostReleasesTheRest() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold).withAmount(300));

            // then
            assertCreated(result);
            assertPostedAndReleased(ledger, payer, payee, 300);
        }

        @Test
        @DisplayName("0원으로 확정하면 전액 확정이 아니라 0원 확정이고, 예약 금액 전부가 풀린다 (P1-3: core가 0원 확정을 막는 근거)")
        void aZeroPostCapturesNothingAndReleasesAll() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold).withAmount(0));

            // then
            assertCreated(result);
            assertPostedAndReleased(ledger, payer, payee, 0);
        }

        @Test
        @DisplayName("예약보다 큰 501원으로 확정하면 거부되고, 같은 ID로 다시 보내도 같은 사유다 — ID를 소진하지 않는다")
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
    @DisplayName("500원 예약을 해제하면")
    class Void {

        @Test
        @DisplayName("예약 금액 전부가 풀리고, 아무것도 확정되지 않는다")
        void aVoidReleasesTheWholeReservation() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold));

            // then
            assertCreated(result);
            assertNothingMoved(ledger, payer);
        }

        @Test
        @DisplayName("같은 해제를 다시 보내면 이미 기록된 것으로 인정된다 (A3)")
        void voidResentIsAlreadyRecorded() {
            // given
            Transfer release = ledger.recorded(voidOf(hold));

            // when
            CreateTransferStatus result = ledger.submit(release);

            // then
            assertAlreadyRecorded(result);
        }

        @Test
        @DisplayName("예약보다 작은 금액을 적으면 금액이 다르다는 사유로 거부된다")
        void aVoidWithASmallerAmountIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold).withAmount(300));

            // then
            assertRejectedFor(result, PendingTransferHasDifferentAmount);
        }

        @Test
        @DisplayName("예약보다 큰 금액을 적으면 예약 금액 초과로 거부된다")
        void aVoidWithALargerAmountIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold).withAmount(501));

            // then
            assertRejectedFor(result, ExceedsPendingTransferAmount);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("확정·해제가 예약의 값을 비워 보내면")
    class Inheritance {

        Transfer tagged;

        @BeforeEach
        void givenAReservationWithUserData() {
            tagged = ledger.recorded(reservation(500).from(payer).to(payee).withUserData64(7).withUserData32(9));
        }

        @Test
        @DisplayName("확정은 예약의 계좌·ledger·code를 물려받아 저장된다")
        void aPostInheritsAccountsLedgerAndCode() {
            // when
            Transfer post = ledger.recorded(postOf(tagged));

            // then
            StoredTransfer stored = ledger.stored(post);
            assertThat(stored.debitAccountId()).as("확정의 보내는 계좌").isEqualTo(payer.id());
            assertThat(stored.creditAccountId()).as("확정의 받는 계좌").isEqualTo(payee.id());
            assertThat(stored.ledger()).as("확정의 ledger").isEqualTo(1);
            assertThat(stored.code()).as("확정의 code").isEqualTo(1);
        }

        @Test
        @DisplayName("확정은 0으로 보낸 user_data_64·32를 예약의 값으로 채운다 (core 가정에 없던 동작)")
        void aPostInheritsUserData() {
            // when
            Transfer post = ledger.recorded(postOf(tagged));

            // then
            StoredTransfer stored = ledger.stored(post);
            assertThat(stored.userData64()).as("확정의 user_data_64").isEqualTo(7);
            assertThat(stored.userData32()).as("확정의 user_data_32").isEqualTo(9);
        }

        @Test
        @DisplayName("해제도 user_data_64·32를 예약의 값으로 채운다")
        void aVoidInheritsUserData() {
            // when
            Transfer release = ledger.recorded(voidOf(tagged));

            // then
            StoredTransfer stored = ledger.stored(release);
            assertThat(stored.userData64()).as("해제의 user_data_64").isEqualTo(7);
            assertThat(stored.userData32()).as("해제의 user_data_32").isEqualTo(9);
        }

        @Test
        @DisplayName("user_data를 물려받은 확정을 같은 본문으로 다시 보내면 이미 기록된 것으로 인정된다")
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
    @DisplayName("확정이 예약과 다른 값을 적으면")
    class DifferentFromTheReservation {

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("differences")
        @DisplayName("그 필드가 다르다는 사유로 거부되고, 예약은 그대로 보류된다")
        void isRejectedAndTheReservationStays(BiFunction<Transfer, Account, Transfer> change,
                CreateTransferStatus reason) {
            // given
            Account other = ledger.openAccount("다른 계좌");

            // when
            CreateTransferStatus result = ledger.submit(change.apply(postOf(hold), other));

            // then
            assertRejectedFor(result, reason);
            assertReserved(ledger, payer, payee, 500);
        }

        static Stream<Arguments> differences() {
            return Stream.of(
                    argumentSet("보내는 계좌를 바꾸면 → PendingTransferHasDifferentDebitAccountId",
                            change((post, other) -> post.withDebitAccount(other)),
                            PendingTransferHasDifferentDebitAccountId),
                    argumentSet("받는 계좌를 바꾸면 → PendingTransferHasDifferentCreditAccountId",
                            change((post, other) -> post.withCreditAccount(other)),
                            PendingTransferHasDifferentCreditAccountId),
                    argumentSet("ledger를 2로 적으면 → PendingTransferHasDifferentLedger",
                            change((post, other) -> post.withLedger(2)), PendingTransferHasDifferentLedger),
                    argumentSet("code를 2로 적으면 → PendingTransferHasDifferentCode",
                            change((post, other) -> post.withCode(2)), PendingTransferHasDifferentCode));
        }

        private static BiFunction<Transfer, Account, Transfer> change(BiFunction<Transfer, Account, Transfer> change) {
            return change;
        }
    }

    @Nested
    @Order(6)
    @DisplayName("이미 끝난 예약에 다시 확정·해제하면")
    class FinishedReservations {

        @Test
        @DisplayName("확정된 예약에 같은 확정을 다시 보내면 '이미 확정됨'이 아니라 이미 기록된 것으로 인정된다 — exists 검사가 먼저다")
        void postingAPostedReservationAgainWithTheSamePostIsAlreadyRecorded() {
            // given
            Transfer post = ledger.recorded(postOf(hold));

            // when
            CreateTransferStatus result = ledger.submit(post);

            // then
            assertAlreadyRecorded(result);
        }

        @Test
        @DisplayName("확정된 예약을 해제하면 '이미 확정됨'으로 거부되고, 같은 ID로 다시 보내도 같은 사유다")
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
        @DisplayName("만료된 예약을 확정하면 만료로 거부되고, 같은 ID로 다시 보내도 같은 사유다")
        void postingAnExpiredReservationIsRejectedEveryTime() {
            // given: 만료 1초 예약이 TB 시계로 만료되었다
            Account buyer = ledger.openAccount("만료될 예약의 구매자");
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
         * 만료 시각은 TB가 예약을 적용한 timestamp에 timeout을 더한 값이다(state_machine.zig L3978,
         * {@code expires_at = timestamp_actual + timeout_ns}). 기준은 TB 클러스터 시계이고 PG {@code now()}가 아니다.
         * 테스트는 TB의 적용 시각을 정할 수 없으므로 저장된 timeout만 확인한다(계획 P2c-3).
         */
        @Test
        @DisplayName("예약은 받은 timeout(초)을 그대로 저장한다 — 만료는 TB 클러스터 시계로 잰다")
        void aReservationKeepsItsTimeout() {
            // when
            Transfer shortHold = ledger.recorded(reservation(1).from(ledger.openAccount("구매자")).to(payee)
                    .withTimeout(1));

            // then
            assertThat(ledger.stored(shortHold).timeoutSeconds()).as("저장된 timeout(초)").isEqualTo(1);
        }
    }

    @Nested
    @Order(7)
    @DisplayName("확정이 실패한 ID를 다시 쓰면")
    class UsedUpIds {

        @Test
        @DisplayName("원장에 없는 예약을 참조한 확정은 예약 없음으로 거부되고, 그 ID는 소진되어 다시 보내면 이미 실패한 ID다")
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
        @DisplayName("잔액 부족으로 실패한 예약을 참조한 확정은 예약 없음으로 거부된다")
        void aPostOfAFailedReservationFindsNoReservation() {
            // given
            Account wallet = ledger.openAccountThatCannotOverdraw("지갑");
            Transfer failedHold = ledger.rejected(reservation(500).from(wallet).to(payee), ExceedsCredits);

            // when
            CreateTransferStatus result = ledger.submit(postOf(failedHold));

            // then
            assertRejectedFor(result, PendingTransferNotFound);
        }

        @Test
        @DisplayName("소진된 ID로 본문을 바꿔 보내도 내용 충돌이 아니라 이미 실패한 ID로 거부된다")
        void aUsedUpIdIgnoresAChangedBody() {
            // given
            Transfer post = ledger.rejected(postOf(reservation(500).from(payer).to(payee)), PendingTransferNotFound);

            // when
            CreateTransferStatus result = ledger.submit(post.withAmount(1));

            // then
            assertRejectedBecauseIdAlreadyFailed(result);
        }

        @Test
        @DisplayName("일시적이지 않은 사유로 거부된 ID는 소진되지 않아, 다시 보내면 같은 사유로 다시 판정된다 (A2)")
        void aNonTransientRejectionIsJudgedAgain() {
            // given
            Account elsewhere = ledger.openAccount("다른 ledger의 가맹점", 2);
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
    @DisplayName("예약이 걸린 계좌가 닫히면")
    class ClosedAccounts {

        @BeforeEach
        void givenThePayerIsClosed() {
            ledger.closeAccount(payer);
        }

        @Test
        @DisplayName("확정은 보내는 계좌가 닫혔다는 사유로 거부된다")
        void aPostOnAClosedAccountIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold));

            // then
            assertRejectedFor(result, DebitAccountAlreadyClosed);
        }

        @Test
        @DisplayName("해제는 허용된다 — 닫힌 계좌에서 유일하게 허용되는 움직임이다")
        void aVoidOnAClosedAccountIsAllowed() {
            // when
            CreateTransferStatus result = ledger.submit(voidOf(hold));

            // then
            assertCreated(result);
        }
    }

    @Nested
    @Order(9)
    @DisplayName("규칙에 맞지 않는 확정이나 이체를 보내면")
    class InvalidRequests {

        @Test
        @DisplayName("예약이 아닌 이체를 참조한 확정은 예약이 아니라는 사유로 거부된다")
        void aPostOfAPlainTransferIsRejected() {
            // given
            Transfer payment = ledger.recorded(transfer(100).from(payer).to(payee));

            // when
            CreateTransferStatus result = ledger.submit(postOf(payment));

            // then
            assertRejectedFor(result, PendingTransferNotPending);
        }

        @Test
        @DisplayName("예약이 아닌 이체에 timeout을 주면 거부된다")
        void aTimeoutOnAPlainTransferIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(transfer(100).from(payer).to(payee).withTimeout(30));

            // then
            assertRejectedFor(result, TimeoutReservedForPendingTransfer);
        }

        @Test
        @DisplayName("확정에 timeout을 주면 거부된다")
        void aTimeoutOnAPostIsRejected() {
            // when
            CreateTransferStatus result = ledger.submit(postOf(hold).withTimeout(30));

            // then
            assertRejectedFor(result, TimeoutReservedForPendingTransfer);
        }

        @Test
        @DisplayName("0원 이체는 기록된다 (0.16.0 이후)")
        void aZeroTransferIsRecorded() {
            // when
            CreateTransferStatus result = ledger.submit(transfer(0).from(payer).to(payee));

            // then
            assertCreated(result);
        }

        @Test
        @DisplayName("0원 예약도 기록된다")
        void aZeroReservationIsRecorded() {
            // when
            CreateTransferStatus result = ledger.submit(reservation(0).from(payer).to(payee));

            // then
            assertCreated(result);
        }
    }
}
