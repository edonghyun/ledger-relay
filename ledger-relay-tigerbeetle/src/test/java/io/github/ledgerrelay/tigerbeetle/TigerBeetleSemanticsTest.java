package io.github.ledgerrelay.tigerbeetle;

import static com.tigerbeetle.CreateTransferStatus.DebitAccountNotFound;
import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertAlreadyRecorded;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertConflictOnAmount;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertNothingMoved;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedBecauseIdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedFor;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;

import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import io.github.ledgerrelay.tigerbeetle.testing.Transfer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 근거: docs.tigerbeetle.com/reference/requests/create_transfers (exists, exists_with_different_*, id_already_failed). */
@DisplayName("TigerBeetle 0.17.9 결과 규칙 — 스펙 §4.4 분류표의 근거")
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
    @DisplayName("500원 결제가 이미 기록된 상태에서")
    class AfterAPaymentIsRecorded {

        Account payer;
        Account payee;
        Transfer payment;

        @BeforeEach
        void givenARecordedPayment() {
            payer = ledger.openAccount("구매자");
            payee = ledger.openAccount("가맹점");
            payment = ledger.recorded(transfer(500).from(payer).to(payee));
        }

        @Test
        @DisplayName("같은 결제를 다시 보내면 이미 기록된 것으로 인정되고, 돈은 한 번만 움직인다")
        void resendingTheSamePayment() {
            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertAlreadyRecorded(result);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        @Test
        @DisplayName("같은 ID에 금액만 바꿔 보내면 내용 충돌로 거부되고, 처음 금액만 남는다 (G7)")
        void resendingWithADifferentAmount() {
            // when
            CreateTransferStatus result = ledger.submit(payment.withAmount(501));

            // then
            assertConflictOnAmount(result);
            assertMovedExactly(ledger, payer, payee, 500);
        }
    }

    @Nested
    @DisplayName("잔액이 부족한 지갑에서 결제하면")
    class PayingFromAnEmptyWallet {

        Account wallet;
        Account merchant;
        Transfer payment;

        @BeforeEach
        void givenAnEmptyWallet() {
            wallet = ledger.openAccountThatCannotOverdraw("지갑");
            merchant = ledger.openAccount("가맹점");
            payment = transfer(100).from(wallet).to(merchant);
        }

        @Test
        @DisplayName("잔액 부족으로 거부되고, 돈은 움직이지 않는다")
        void isRejectedForInsufficientFunds() {
            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertRejectedFor(result, ExceedsCredits);
            assertNothingMoved(ledger, wallet);
        }

        @Test
        @DisplayName("같은 결제를 다시 보내면 이 ID는 이미 실패한 것으로 거부된다")
        void resendingTheFailedPayment() {
            // given
            ledger.rejected(payment, ExceedsCredits);

            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertRejectedBecauseIdAlreadyFailed(result);
        }

        @Test
        @DisplayName("충전한 뒤에도 같은 ID는 실패로 남고, 새 ID로 보내야 결제된다 (D7: 재시도는 새 명령)")
        void onlyANewIdSucceedsAfterTopUp() {
            // given: 결제가 한 번 실패했고, 그 뒤 100원을 충전했다
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
    @DisplayName("존재하지 않는 계좌에서 출금하면 (P4)")
    class PayingFromAnUnknownAccount {

        @Test
        @DisplayName("잘못된 요청으로 거부된다")
        void isRejectedAsUnknownAccount() {
            // given
            Account unknown = new Account("원장에 없는 계좌", UInt128.id());
            Account merchant = ledger.openAccount("가맹점");

            // when
            CreateTransferStatus result = ledger.submit(transfer(1).from(unknown).to(merchant));

            // then
            assertRejectedFor(result, DebitAccountNotFound);
        }
    }
}
