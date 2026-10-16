package io.github.ledgerrelay.core;

import static io.github.ledgerrelay.core.CommandKind.POST;
import static io.github.ledgerrelay.core.CommandKind.RESERVE;
import static io.github.ledgerrelay.core.CommandKind.TRANSFER;
import static io.github.ledgerrelay.core.CommandKind.VOID;
import static io.github.ledgerrelay.core.LedgerCommand.AMOUNT_MAX;
import static io.github.ledgerrelay.core.LedgerCommand.UINT128_MAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.math.BigInteger;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("명령 모델 — 스펙 §6 빌더와 §5.1 정규화")
class LedgerCommandTest {

    @Nested
    @DisplayName("올바른 명령을 만들면")
    class ValidCommands {

        @Test
        @DisplayName("이체는 적은 값을 그대로 담고, 생략한 user_data는 0이 된다")
        void transferKeepsWhatWasWritten() {
            // when
            LedgerCommand payment = LedgerCommand.transfer("pay-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();

            // then
            assertThat(payment).isEqualTo(new LedgerCommand("pay-1", TRANSFER, "order-42",
                    big(1001), big(2002), big(500), 1, 10, 0, null, 0, 0));
        }

        @Test
        @DisplayName("예약은 만료 시간(timeoutSeconds)을 함께 담는다")
        void reserveKeepsItsTimeout() {
            // when
            LedgerCommand hold = LedgerCommand.reserve("hold-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).timeoutSeconds(30).build();

            // then
            assertThat(hold).isEqualTo(new LedgerCommand("hold-1", RESERVE, "order-42",
                    big(1001), big(2002), big(500), 1, 10, 30, null, 0, 0));
        }

        @Test
        @DisplayName("금액을 생략한 확정은 전액 확정(AMOUNT_MAX)이 되고, 계좌·ledger·code는 예약을 따르도록 0이 된다")
        void postWithoutAmountCapturesEverything() {
            // when
            LedgerCommand capture = LedgerCommand.post("capture-1").orderingKey("order-42")
                    .reservation("hold-1").build();

            // then
            assertThat(capture).isEqualTo(new LedgerCommand("capture-1", POST, "order-42",
                    BigInteger.ZERO, BigInteger.ZERO, AMOUNT_MAX, 0, 0, 0, "hold-1", 0, 0));
        }

        @Test
        @DisplayName("금액을 적은 확정은 그 금액만 확정한다(부분 확정)")
        void postWithAmountCapturesPart() {
            // when
            LedgerCommand capture = LedgerCommand.post("capture-1").orderingKey("order-42")
                    .reservation("hold-1").amount(300).build();

            // then
            assertThat(capture.amount()).isEqualTo(big(300));
        }

        @Test
        @DisplayName("해제는 금액 0으로 담긴다")
        void voidHasNoAmount() {
            // when
            LedgerCommand release = LedgerCommand.voidReservation("release-1").orderingKey("order-42")
                    .reservation("hold-1").build();

            // then
            assertThat(release).isEqualTo(new LedgerCommand("release-1", VOID, "order-42",
                    BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 0, 0, 0, "hold-1", 0, 0));
        }
    }

    @Nested
    @DisplayName("규칙을 어긴 명령을 만들면")
    class InvalidCommands {

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("invalidCommands")
        @DisplayName("InvalidCommandException으로 거부되고, 메시지에 무엇이 틀렸는지 적힌다")
        void isRejectedWithTheReason(ThrowingCallable building, String reason) {
            assertRejectedAsInvalid(building, reason);
        }

        static Stream<Arguments> invalidCommands() {
            return Stream.of(
                    argumentSet("commandId가 비어 있으면",
                            build(() -> LedgerCommand.transfer(" ").orderingKey("order-42")
                                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).build()),
                            "commandId가 필요합니다"),
                    argumentSet("orderingKey가 없으면",
                            build(() -> aTransfer().orderingKey(null).build()), "orderingKey가 필요합니다"),
                    argumentSet("보내는 계좌가 0(없음)이면",
                            build(() -> aTransfer().debit(0).build()), "debitAccountId가 필요합니다"),
                    argumentSet("받는 계좌가 2^128-1(TB 예약값)이면",
                            build(() -> aTransfer().credit(UINT128_MAX).build()),
                            "creditAccountId는 1 이상 2^128-2 이하여야 합니다"),
                    argumentSet("보내는 계좌와 받는 계좌가 같으면",
                            build(() -> aTransfer().credit(1001).build()), "같은 계좌일 수 없습니다"),
                    argumentSet("이체에 금액이 없으면",
                            build(() -> LedgerCommand.transfer("pay-1").orderingKey("order-42")
                                    .debit(1001).credit(2002).ledger(1).code(10).build()),
                            "amount가 필요합니다"),
                    argumentSet("금액이 음수면",
                            build(() -> aTransfer().amount(-1).build()), "amount는 0 이상 2^128-1 이하여야 합니다"),
                    argumentSet("금액이 2^128 이상이면",
                            build(() -> aTransfer().amount(UINT128_MAX.add(BigInteger.ONE)).build()),
                            "amount는 0 이상 2^128-1 이하여야 합니다"),
                    argumentSet("ledger가 0이면",
                            build(() -> aTransfer().ledger(0).build()), "ledger는 1 이상 2^32-1 이하여야 합니다"),
                    argumentSet("code가 65535를 넘으면",
                            build(() -> aTransfer().code(65536).build()), "code는 1 이상 65535 이하여야 합니다"),
                    argumentSet("이체에 timeoutSeconds를 주면",
                            build(() -> aTransfer().timeoutSeconds(30).build()), "timeoutSeconds는 reserve 전용입니다"),
                    argumentSet("예약의 timeoutSeconds가 2^32 이상이면",
                            build(() -> aReserve().timeoutSeconds(1L << 32).build()),
                            "timeoutSeconds는 0 이상 2^32-1 이하여야 합니다"),
                    argumentSet("이체에 reservation을 주면",
                            build(() -> aTransfer().reservation("hold-1").build()), "reservation은 post·void 전용입니다"),
                    argumentSet("확정에 reservation이 없으면",
                            build(() -> LedgerCommand.post("capture-1").orderingKey("order-42").build()),
                            "post·void에는 reservation이 필요합니다"),
                    argumentSet("확정에 보내는 계좌를 주면",
                            build(() -> aPost().debit(1001).build()), "post·void는 예약의 계좌·ledger·code를 따르므로"),
                    argumentSet("해제에 금액을 주면",
                            build(() -> aVoid().amount(100).build()), "void는 amount를 받지 않습니다"));
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 기준 이체: order-42 키에서 1001 → 2002로 500, ledger 1, code 10. 각 행은 여기서 한 가지만 바꾼다. */
    private static LedgerCommand.Builder aTransfer() {
        return LedgerCommand.transfer("pay-1").orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10);
    }

    /** 기준 예약: 기준 이체와 같은 값에 만료 30초. */
    private static LedgerCommand.Builder aReserve() {
        return LedgerCommand.reserve("hold-1").orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).timeoutSeconds(30);
    }

    /** 기준 확정: hold-1 전액 확정. */
    private static LedgerCommand.Builder aPost() {
        return LedgerCommand.post("capture-1").orderingKey("order-42").reservation("hold-1");
    }

    /** 기준 해제: hold-1 해제. */
    private static LedgerCommand.Builder aVoid() {
        return LedgerCommand.voidReservation("release-1").orderingKey("order-42").reservation("hold-1");
    }

    private static ThrowingCallable build(ThrowingCallable building) {
        return building;
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static void assertRejectedAsInvalid(ThrowingCallable building, String reason) {
        assertThatThrownBy(building)
                .as("잘못된 명령으로 거부되기(InvalidCommandException: %s)", reason)
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining(reason);
    }
}
