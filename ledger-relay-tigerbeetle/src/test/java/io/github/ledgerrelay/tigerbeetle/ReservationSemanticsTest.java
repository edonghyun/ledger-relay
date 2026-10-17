package io.github.ledgerrelay.tigerbeetle;

import static com.tigerbeetle.CreateTransferStatus.ExceedsPendingTransferAmount;
import static com.tigerbeetle.CreateTransferStatus.PendingTransferHasDifferentAmount;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertAlreadyRecorded;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertPostedAndReleased;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedFor;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertReserved;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.postOf;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.reservation;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.voidOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import io.github.ledgerrelay.tigerbeetle.testing.Transfer;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

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
}
