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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

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

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("contentChanges")
        @DisplayName("같은 ID에 금액이 아닌 내용을 바꿔 보내면 바뀐 필드의 내용 충돌로 거부되고, 처음 이체만 남는다 (G7)")
        void resendingWithOtherContentChanged(UnaryOperator<Transfer> change, CreateTransferStatus conflict) {
            // when
            CreateTransferStatus result = ledger.submit(change.apply(payment));

            // then
            assertContentConflict(result, conflict);
            assertMovedExactly(ledger, payer, payee, 500);
        }

        static Stream<Arguments> contentChanges() {
            return Stream.of(
                    argumentSet("보내는 계좌를 바꾸면 → ExistsWithDifferentDebitAccountId",
                            change(t -> t.withDebitAccount(ledger.openAccount("다른 구매자"))),
                            ExistsWithDifferentDebitAccountId),
                    argumentSet("받는 계좌를 바꾸면 → ExistsWithDifferentCreditAccountId",
                            change(t -> t.withCreditAccount(ledger.openAccount("다른 가맹점"))),
                            ExistsWithDifferentCreditAccountId),
                    argumentSet("ledger를 바꾸면 → ExistsWithDifferentLedger",
                            change(t -> t.withLedger(2)), ExistsWithDifferentLedger),
                    argumentSet("code를 바꾸면 → ExistsWithDifferentCode",
                            change(t -> t.withCode(2)), ExistsWithDifferentCode),
                    argumentSet("user_data_128(모듈의 명령 역참조)을 바꾸면 → ExistsWithDifferentUserData128",
                            change(t -> t.withUserData128(42)), ExistsWithDifferentUserData128),
                    argumentSet("user_data_64를 바꾸면 → ExistsWithDifferentUserData64",
                            change(t -> t.withUserData64(42)), ExistsWithDifferentUserData64),
                    argumentSet("user_data_32를 바꾸면 → ExistsWithDifferentUserData32",
                            change(t -> t.withUserData32(42)), ExistsWithDifferentUserData32));
        }

        private static UnaryOperator<Transfer> change(UnaryOperator<Transfer> change) {
            return change;
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

        Account unknown;
        Account merchant;
        Transfer payment;

        @BeforeEach
        void givenAnAccountNotYetOpened() {
            unknown = Account.notOpened("원장에 없는 계좌");
            merchant = ledger.openAccount("가맹점");
            payment = transfer(1).from(unknown).to(merchant);
        }

        @Test
        @DisplayName("잘못된 요청으로 거부된다")
        void isRejectedAsUnknownAccount() {
            // when
            CreateTransferStatus result = ledger.submit(payment);

            // then
            assertRejectedFor(result, DebitAccountNotFound);
        }

        @Test
        @DisplayName("그 계좌를 연 뒤에도 같은 ID는 실패로 남고, 새 ID로 보내야 결제된다")
        void onlyANewIdSucceedsAfterOpeningTheAccount() {
            // given: 없는 계좌에서 보낸 결제가 실패했고, 그 뒤 그 계좌를 열었다
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
