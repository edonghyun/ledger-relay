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

@DisplayName("원장 어휘 — docs/testing.md §3·§5: 전제와 효과 확인이 틀린 상황에서 제대로 멈추는가")
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
    @DisplayName("given 전제 메서드는")
    class Preconditions {

        @Test
        @DisplayName("recorded: 실제로는 거부되는 이체로 '기록됨' 전제를 만들면 준비 단계에서 멈춘다")
        void recordedStopsWhenTheTransferIsRejected() {
            Account wallet = ledger.openAccountThatCannotOverdraw("지갑");
            Account merchant = ledger.openAccount("가맹점");

            assertThatThrownBy(() -> ledger.recorded(transfer(100).from(wallet).to(merchant)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given 준비 실패")
                    .hasMessageContaining("ExceedsCredits");
        }

        @Test
        @DisplayName("rejected: 실제로는 기록되는 이체로 '거부됨' 전제를 만들면 준비 단계에서 멈춘다")
        void rejectedStopsWhenTheTransferIsCreated() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");

            assertThatThrownBy(() -> ledger.rejected(transfer(100).from(payer).to(payee), ExceedsCredits))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given 준비 실패")
                    .hasMessageContaining("Created");
        }

        @Test
        @DisplayName("closeAccount: 이미 닫은 계좌를 다시 닫으면 준비 단계에서 멈춘다")
        void closeAccountRefusesAnAlreadyClosedAccount() {
            Account payer = ledger.openAccount("닫을 계좌");
            ledger.closeAccount(payer);

            assertThatThrownBy(() -> ledger.closeAccount(payer))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given 준비 실패")
                    .hasMessageContaining("닫을 계좌");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("예약 이체를 보내면")
    class Reservations {

        @Test
        @DisplayName("reservation: 금액이 보류될 뿐 확정되지 않는다")
        void reservingHoldsTheAmountWithoutPosting() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");

            ledger.recorded(reservation(500).from(payer).to(payee));

            Balance balance = ledger.balanceOf(payer);
            assertThat(balance.debitsPosted()).as("구매자의 확정 출금").isZero();
            assertThat(balance.debitsPending()).as("구매자의 보류 출금").isEqualTo(500);
        }

        @Test
        @DisplayName("stored: TB가 저장한 값을 읽는다")
        void storedReadsWhatTigerBeetleKept() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            Transfer payment = ledger.recorded(transfer(500).from(payer).to(payee));

            StoredTransfer stored = ledger.stored(payment);

            assertThat(stored.amount()).as("저장된 금액").isEqualTo(BigInteger.valueOf(500));
            assertThat(stored.ledger()).as("저장된 ledger").isEqualTo(1);
        }

        @Test
        @DisplayName("stored: 원장에 없는 이체를 읽으면 실패한다")
        void storedFailsForAnUnknownTransfer() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            Transfer neverSent = transfer(500).from(payer).to(payee);

            assertThatThrownBy(() -> ledger.stored(neverSent))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("원장에 없습니다");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("나중에 여는 계좌는")
    class AccountsOpenedLater {

        @Test
        @DisplayName("open: 열기 전에는 원장에 없고, 열면 그 ID 그대로 조회된다")
        void openUsesTheSameId() {
            Account later = Account.notOpened("나중에 열 계좌");
            assertThatThrownBy(() -> ledger.balanceOf(later))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("원장에 없습니다");

            ledger.open(later);

            assertThat(ledger.balanceOf(later).isUntouched()).isTrue();
        }

        @Test
        @DisplayName("openAccount(이름, ledger): 그 ledger에 계좌를 연다 — 다른 ledger 계좌로는 이체할 수 없다")
        void openAccountOnAnotherLedger() {
            Account payer = ledger.openAccount("구매자");
            Account elsewhere = ledger.openAccount("다른 ledger의 가맹점", 2);

            assertThatThrownBy(() -> ledger.recorded(transfer(1).from(payer).to(elsewhere)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given 준비 실패")
                    .hasMessageContaining("Ledger");
        }

        @Test
        @DisplayName("open: 이미 열린 계좌를 다시 열면 준비 단계에서 멈춘다")
        void openStopsWhenTheAccountAlreadyExists() {
            Account opened = ledger.openAccount("이미 연 계좌");

            assertThatThrownBy(() -> ledger.open(opened))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("given 준비 실패")
                    .hasMessageContaining("이미 연 계좌");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("효과를 확인하는 assert는")
    class EffectAssertions {

        @Test
        @DisplayName("assertMovedExactly: 실제로 움직인 금액이 다르면 기대 금액과 실제 잔액을 담아 실패한다")
        void movedExactlyFailsOnADifferentAmount() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            ledger.recorded(transfer(500).from(payer).to(payee));

            assertThatThrownBy(() -> assertMovedExactly(ledger, payer, payee, 501))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("정확히 501")
                    .hasMessageContaining("출금 500");
        }

        @Test
        @DisplayName("assertNothingMoved: 돈이 움직인 계좌면 실패한다")
        void nothingMovedFailsAfterAMove() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            ledger.recorded(transfer(1).from(payer).to(payee));

            assertThatThrownBy(() -> assertNothingMoved(ledger, payee))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("가맹점")
                    .hasMessageContaining("입금 1");
        }

        @Test
        @DisplayName("assertStillWaitingAfter: 이미 응답이 온 요청이면 실패한다")
        void stillWaitingFailsWhenTheReplyArrived() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            PendingTransfer pending = ledger.submitInBackground(transfer(1).from(payer).to(payee));
            pending.awaitResult(Duration.ofSeconds(30));

            assertThatThrownBy(() -> assertStillWaitingAfter(pending, Duration.ofMillis(100)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("응답");
        }

        @Test
        @DisplayName("assertReserved: 보류가 아니라 확정된 금액이면 실패한다")
        void assertReservedFailsWhenTheAmountWasPosted() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            ledger.recorded(transfer(500).from(payer).to(payee));

            assertThatThrownBy(() -> assertReserved(ledger, payer, payee, 500))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("보류")
                    .hasMessageContaining("출금 500");
        }

        @Test
        @DisplayName("assertPostedAndReleased: 아직 보류 중인 금액이 있으면 실패한다")
        void assertPostedAndReleasedFailsWhileStillReserved() {
            Account payer = ledger.openAccount("구매자");
            Account payee = ledger.openAccount("가맹점");
            ledger.recorded(reservation(500).from(payer).to(payee));

            assertThatThrownBy(() -> assertPostedAndReleased(ledger, payer, payee, 0))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("보류 출금 500");
        }
    }
}
