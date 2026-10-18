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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("명령 모델 — 빌더와 정규화")
class LedgerCommandTest {

    @Nested
    @Order(1)
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
        @Test
        @DisplayName("UTF-8 512바이트의 commandId는 담긴다 — 상한 바로 아래")
        void aCommandIdOf512BytesIsAccepted() {
            // when
            LedgerCommand payment = aTransfer("a".repeat(512)).build();

            // then
            assertThat(payment.commandId()).hasSize(512);
        }

        @Test
        @DisplayName("짝이 맞는 서로게이트(이모지)는 순서 키에 그대로 담긴다")
        void aTransferWithAPairedSurrogateIsAccepted() {
            // when
            LedgerCommand payment = aTransfer("pay-1").orderingKey("주문-😀").build();

            // then
            assertThat(payment.orderingKey()).isEqualTo("주문-😀");
        }

        @Test
        @DisplayName("TB 필드의 최댓값(ledger 2^32-1, code 65535, 만료 2^32-1초)은 그대로 담긴다")
        void acceptsTheLargestTigerBeetleValues() {
            // when
            LedgerCommand hold = LedgerCommand.reserve("hold-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500)
                    .ledger(4_294_967_295L).code(65_535).timeoutSeconds(4_294_967_295L).build();

            // then
            assertThat(hold.ledger()).as("ledger").isEqualTo(4_294_967_295L);
            assertThat(hold.code()).as("code").isEqualTo(65_535);
            assertThat(hold.timeoutSeconds()).as("timeoutSeconds").isEqualTo(4_294_967_295L);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("규칙을 어긴 명령을 만들면")
    class InvalidCommands {

        @ParameterizedTest(name = "{argumentSetName} → \"{1}\"", quoteTextArguments = false)
        @MethodSource("invalidCommands")
        @DisplayName("만들 수 없고, 무엇이 틀렸는지 알려 준다")
        void isRejectedWithTheReason(ThrowingCallable building, String reason) {
            assertRejectedAsInvalid(building, reason);
        }

        static Stream<Arguments> invalidCommands() {
            return Stream.of(
                    argumentSet("commandId가 비어 있으면",
                            (ThrowingCallable) () -> aTransfer(" ").build(),
                            "commandId가 필요합니다"),
                    argumentSet("orderingKey가 없으면",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey(null).build(), "orderingKey가 필요합니다"),
                    argumentSet("보내는 계좌가 0(없음)이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").debit(0).build(), "debitAccountId가 필요합니다"),
                    argumentSet("받는 계좌가 2^128-1(TB 예약값)이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").credit(UINT128_MAX).build(),
                            "creditAccountId는 1 이상 2^128-2 이하여야 합니다"),
                    argumentSet("보내는 계좌와 받는 계좌가 같으면",
                            (ThrowingCallable) () -> aTransfer("pay-1").credit(1001).build(), "같은 계좌일 수 없습니다"),
                    argumentSet("이체에 금액이 없으면",
                            (ThrowingCallable) () -> aTransferWithoutAmount().build(),
                            "amount가 필요합니다"),
                    argumentSet("금액이 음수면",
                            (ThrowingCallable) () -> aTransfer("pay-1").amount(-1).build(), "amount는 0 이상 2^128-1 이하여야 합니다"),
                    argumentSet("금액이 2^128 이상이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").amount(UINT128_MAX.add(BigInteger.ONE)).build(),
                            "amount는 0 이상 2^128-1 이하여야 합니다"),
                    argumentSet("ledger가 0이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").ledger(0).build(), "ledger는 1 이상 2^32-1 이하여야 합니다"),
                    argumentSet("code가 65535를 넘으면",
                            (ThrowingCallable) () -> aTransfer("pay-1").code(65536).build(), "code는 1 이상 65535 이하여야 합니다"),
                    argumentSet("이체에 timeoutSeconds를 주면",
                            (ThrowingCallable) () -> aTransfer("pay-1").timeoutSeconds(30).build(), "timeoutSeconds는 reserve 전용입니다"),
                    argumentSet("예약의 timeoutSeconds가 2^32 이상이면",
                            (ThrowingCallable) () -> aReserve().timeoutSeconds(1L << 32).build(),
                            "timeoutSeconds는 0 이상 2^32-1 이하여야 합니다"),
                    argumentSet("이체에 reservation을 주면",
                            (ThrowingCallable) () -> aTransfer("pay-1").reservation("hold-1").build(), "reservation은 post·void 전용입니다"),
                    argumentSet("확정에 reservation이 없으면",
                            (ThrowingCallable) () -> LedgerCommand.post("capture-1").orderingKey("order-42").build(),
                            "post·void에는 reservation이 필요합니다"),
                    argumentSet("확정에 보내는 계좌를 주면",
                            (ThrowingCallable) () -> aPost().debit(1001).build(), "post·void는 예약의 계좌·ledger·code를 따르므로"),
                    argumentSet("확정 금액이 0이면 — TB는 0원 확정 후 나머지를 풀므로, 전액 확정을 기대한 사용자가 돈을 못 받는다",
                            (ThrowingCallable) () -> aPost().amount(0).build(), "post의 amount는 0일 수 없습니다"),
                    argumentSet("orderingKey가 공백이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey(" ").build(), "orderingKey가 필요합니다"),
                    argumentSet("보내는 계좌가 음수면",
                            (ThrowingCallable) () -> aTransfer("pay-1").debit(-5).build(), "debitAccountId는 1 이상 2^128-2 이하여야 합니다"),
                    argumentSet("ledger가 2^32 이상이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").ledger(1L << 32).build(), "ledger는 1 이상 2^32-1 이하여야 합니다"),
                    argumentSet("code가 0이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").code(0).build(), "code는 1 이상 65535 이하여야 합니다"),
                    argumentSet("예약의 timeoutSeconds가 음수면",
                            (ThrowingCallable) () -> aReserve().timeoutSeconds(-1).build(),
                            "timeoutSeconds는 0 이상 2^32-1 이하여야 합니다"),
                    argumentSet("확정에 timeoutSeconds를 주면",
                            (ThrowingCallable) () -> aPost().timeoutSeconds(30).build(), "timeoutSeconds는 reserve 전용입니다"),
                    argumentSet("확정에 받는 계좌를 주면",
                            (ThrowingCallable) () -> aPost().credit(2002).build(), "post·void는 예약의 계좌·ledger·code를 따르므로"),
                    argumentSet("확정에 ledger를 주면",
                            (ThrowingCallable) () -> aPost().ledger(1).build(), "post·void는 예약의 계좌·ledger·code를 따르므로"),
                    argumentSet("해제에 code를 주면",
                            (ThrowingCallable) () -> aVoid().code(10).build(), "post·void는 예약의 계좌·ledger·code를 따르므로"),
                    argumentSet("해제에 reservation이 없으면",
                            (ThrowingCallable) () -> LedgerCommand.voidReservation("release-1").orderingKey("order-42").build(),
                            "post·void에는 reservation이 필요합니다"),
                    argumentSet("확정의 reservation이 공백이면",
                            (ThrowingCallable) () -> aPost().reservation(" ").build(), "post·void에는 reservation이 필요합니다"),
                    argumentSet("해제에 금액을 주면",
                            (ThrowingCallable) () -> aVoid().amount(100).build(), "void는 amount를 받지 않습니다"),
                    argumentSet("commandId에 U+0000이 있으면 — PG text가 받지 않아 접수가 SQL 오류로 앱 트랜잭션을 깬다",
                            (ThrowingCallable) () -> aTransfer("pay\u00001").build(),
                            "commandId에 U+0000이나 짝 없는 서로게이트를 쓸 수 없습니다"),
                    argumentSet("순서 키에 짝 없는 서로게이트가 있으면 — UTF-8로 바꿀 때 '?'가 되어 해시가 충돌한다",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey("order-\uD800").build(),
                            "orderingKey에 U+0000이나 짝 없는 서로게이트를 쓸 수 없습니다"),
                    argumentSet("확정의 reservation에 U+0000이 있으면",
                            (ThrowingCallable) () -> aPost().reservation("hold\u00001").build(),
                            "reservationCommandId에 U+0000이나 짝 없는 서로게이트를 쓸 수 없습니다"),
                    argumentSet("commandId가 UTF-8 513바이트면 — PG 인덱스 항목 한계에 닿기 전에 막는다",
                            (ThrowingCallable) () -> aTransfer("a".repeat(513)).build(),
                            "commandId는 UTF-8 512바이트 이하여야 합니다"),
                    argumentSet("순서 키가 한글 171자(513바이트)면",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey("가".repeat(171)).build(),
                            "orderingKey는 UTF-8 512바이트 이하여야 합니다"),
                    argumentSet("보내는 계좌가 null이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").debit((BigInteger) null).build(),
                            "debitAccountId가 필요합니다"),
                    argumentSet("받는 계좌가 null이면",
                            (ThrowingCallable) () -> aTransfer("pay-1").credit((BigInteger) null).build(),
                            "creditAccountId가 필요합니다"),
                    argumentSet("확정이 자기 자신을 reservation으로 적으면",
                            (ThrowingCallable) () -> LedgerCommand.post("capture-1").orderingKey("order-42")
                                    .reservation("capture-1").build(),
                            "post·void는 자기 자신을 reservation으로 참조할 수 없습니다"));
        }
    }

    @Nested
    @Order(3)
    @DisplayName("문자열이 commandId로 접수될 수 있는지 물으면 — 조회·포기가 DB에 보내기 전에 쓴다 (F20)")
    class AcceptableIds {

        @Test
        @DisplayName("UTF-8 513바이트면 접수될 수 없다")
        void anIdOver512BytesIsNotAcceptable() {
            assertThat(LedgerCommand.isAcceptableId("가".repeat(171))).as("513바이트 id를 접수할 수 있는가").isFalse();
        }

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
                pay-1        | true
                주문-😀      | true
                null         | false
                ' '          | false
                pay\u00001   | false
                pay-\uD800   | false
                \uDC00-pay   | false
                pay\uD800x   | false
                """)
        @DisplayName("공백이 아니고 U+0000과 짝 없는 서로게이트가 없을 때만 그렇다")
        void answersWhetherTheIdCouldHaveBeenAccepted(String commandId, boolean acceptable) {
            // 표의 \\uXXXX는 컴파일러가 실제 문자(U+0000, 서로게이트 반쪽)로 바꾼다
            assertThat(LedgerCommand.isAcceptableId(commandId)).as("%s를 commandId로 접수할 수 있는가", commandId)
                    .isEqualTo(acceptable);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 기준 이체: order-42 키에서 1001 → 2002로 500, ledger 1, code 10. 각 행은 여기서 한 가지만 바꾼다. */
    private static LedgerCommand.Builder aTransfer(String commandId) {
        return aTransferWithoutAmount(commandId).amount(500);
    }

    /** 금액만 빠진 기준 이체. */
    private static LedgerCommand.Builder aTransferWithoutAmount() {
        return aTransferWithoutAmount("pay-1");
    }

    private static LedgerCommand.Builder aTransferWithoutAmount(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).ledger(1).code(10);
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

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static void assertRejectedAsInvalid(ThrowingCallable building, String reason) {
        assertThatThrownBy(building)
                .as("명령을 만들 수 없기(InvalidCommandException: %s)", reason)
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining(reason);
    }
}
