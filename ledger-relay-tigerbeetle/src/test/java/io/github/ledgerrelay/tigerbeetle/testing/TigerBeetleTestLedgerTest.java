package io.github.ledgerrelay.tigerbeetle.testing;

import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertStillWaitingAfter;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    }

    @Nested
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
    }
}
