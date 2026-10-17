package io.github.ledgerrelay.core.domain;

import static io.github.ledgerrelay.core.LedgerCommand.AMOUNT_MAX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ledgerrelay.core.LedgerCommand;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("내용 해시 — 스펙 §5.1 정규화, G7 내용 무결성")
class ContentHashTest {

    @Nested
    @Order(1)
    @DisplayName("같은 내용이면")
    class SameContent {

        @Test
        @DisplayName("빌더에 값을 넣는 순서가 달라도 해시는 같다")
        void builderOrderDoesNotMatter() {
            // given
            LedgerCommand one = LedgerCommand.transfer("pay-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();
            LedgerCommand other = LedgerCommand.transfer("pay-1").code(10).ledger(1)
                    .amount(500).credit(2002).debit(1001).orderingKey("order-42").build();

            // then
            assertSameHash(one, other);
        }

        @Test
        @DisplayName("commandId가 달라도 해시는 같다 — commandId는 비교 기준이지 내용이 아니다")
        void commandIdIsNotContent() {
            // given
            LedgerCommand one = aTransfer("pay-1").build();
            LedgerCommand other = aTransfer("pay-2").build();

            // then
            assertSameHash(one, other);
        }

        @Test
        @DisplayName("금액을 생략한 확정과 AMOUNT_MAX를 적은 확정은 같은 해시다")
        void omittedPostAmountEqualsAmountMax() {
            // given
            LedgerCommand omitted = aPost().build();
            LedgerCommand explicit = aPost().amount(AMOUNT_MAX).build();

            // then
            assertSameHash(omitted, explicit);
        }

        @Test
        @DisplayName("user_data를 생략한 이체와 0을 적은 이체는 같은 해시다")
        void omittedUserDataEqualsZero() {
            // given
            LedgerCommand omitted = aTransfer("pay-1").build();
            LedgerCommand explicit = aTransfer("pay-1").userData64(0).userData32(0).build();

            // then
            assertSameHash(omitted, explicit);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("내용이 하나라도 다르면")
    class DifferentContent {

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("oneFieldApart")
        @DisplayName("해시가 다르다")
        void hashDiffers(LedgerCommand one, LedgerCommand other, String field) {
            assertDifferentHash(one, other);
        }

        static Stream<Arguments> oneFieldApart() {
            return Stream.of(
                    argumentSet("kind가 다르면 (이체 vs 예약)",
                            aTransfer("c").build(), aReserveLikeTheTransfer("c").build(), "kind"),
                    argumentSet("orderingKey가 다르면",
                            aTransfer("c").build(), aTransfer("c").orderingKey("order-43").build(), "orderingKey"),
                    argumentSet("보내는 계좌가 다르면",
                            aTransfer("c").build(), aTransfer("c").debit(1003).build(), "debitAccountId"),
                    argumentSet("받는 계좌가 다르면",
                            aTransfer("c").build(), aTransfer("c").credit(2003).build(), "creditAccountId"),
                    argumentSet("금액이 다르면",
                            aTransfer("c").build(), aTransfer("c").amount(501).build(), "amount"),
                    argumentSet("ledger가 다르면",
                            aTransfer("c").build(), aTransfer("c").ledger(2).build(), "ledger"),
                    argumentSet("code가 다르면",
                            aTransfer("c").build(), aTransfer("c").code(11).build(), "code"),
                    argumentSet("code가 다르면 (10 vs 266: 하위 8비트가 같은 값)",
                            aTransfer("c").build(), aTransfer("c").code(266).build(), "code"),
                    argumentSet("timeoutSeconds가 다르면",
                            aReserveLikeTheTransfer("c").timeoutSeconds(30).build(),
                            aReserveLikeTheTransfer("c").timeoutSeconds(31).build(), "timeoutSeconds"),
                    argumentSet("reservation이 다르면",
                            aPost().build(), aPost().reservation("hold-2").build(), "reservationCommandId"),
                    argumentSet("user_data_64가 다르면",
                            aTransfer("c").build(), aTransfer("c").userData64(7).build(), "userData64"),
                    argumentSet("user_data_32가 다르면",
                            aTransfer("c").build(), aTransfer("c").userData32(7).build(), "userData32"));
        }

        @Test
        @DisplayName("위 표는 해시 필드를 하나도 빠뜨리지 않는다")
        void tableCoversEveryHashedField() {
            // given: 표의 행마다 셋째 칸이 그 행이 바꾼 필드다
            List<Object> coveredByTheTable = oneFieldApart().map(row -> row.get()[2]).toList();

            // then
            assertThat(coveredByTheTable).as("표가 바꿔 보는 필드").containsAll(ContentHash.HASHED_FIELDS);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("해시는 DB에 저장되므로 v1 인코딩은 고정이다 — 바꾸려면 VERSION을 올린다")
    class FixedEncoding {

        @Test
        @DisplayName("기준 이체(order-42, 1001 → 2002, 500, ledger 1, code 10)의 해시는 13ff1a83…다")
        void knownVectorForTheReferenceTransfer() {
            // when
            String hash = ContentHash.of(aTransfer("pay-1").build());

            // then
            assertPinnedHash(hash, "13ff1a83e735898e91f4e1f133b9818de72293f991856e926a0f4f8021c85b09");
        }

        @Test
        @DisplayName("user_data는 부호 없는 정수로 적는다: -1은 2^64-1과 2^32-1로 읽힌다")
        void userDataIsHashedUnsigned() {
            // when
            LedgerCommand command = aTransfer("pay-1").userData64(-1L).userData32(-1).build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=8:transfer
                    orderingKey=8:order-42
                    debitAccountId=4:1001
                    creditAccountId=4:2002
                    amount=3:500
                    ledger=1:1
                    code=2:10
                    timeoutSeconds=1:0
                    reservationCommandId=0:
                    userData64=20:18446744073709551615
                    userData32=10:4294967295
                    """);
        }

        @Test
        @DisplayName("한글 순서 키는 UTF-8 바이트 길이로 적는다: \"주문-42\"는 9바이트다(한글 두 글자 3바이트씩 + 3)")
        void nonAsciiValuesUseUtf8ByteLength() {
            // when
            LedgerCommand command = aTransfer("pay-1").orderingKey("주문-42").build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=8:transfer
                    orderingKey=9:주문-42
                    debitAccountId=4:1001
                    creditAccountId=4:2002
                    amount=3:500
                    ledger=1:1
                    code=2:10
                    timeoutSeconds=1:0
                    reservationCommandId=0:
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("금액을 생략한 확정(hold-1 전액)은 계좌 0, AMOUNT_MAX 39자리, reservation을 적는다")
        void knownVectorForAFullPost() {
            // when
            LedgerCommand command = aPost().build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=4:post
                    orderingKey=8:order-42
                    debitAccountId=1:0
                    creditAccountId=1:0
                    amount=39:340282366920938463463374607431768211455
                    ledger=1:0
                    code=1:0
                    timeoutSeconds=1:0
                    reservationCommandId=6:hold-1
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("해제(hold-1)는 kind \"void\"를 로캘과 무관하게 소문자로 적는다")
        void knownVectorForAVoid() {
            // when
            LedgerCommand command = LedgerCommand.voidReservation("release-1").orderingKey("order-42")
                    .reservation("hold-1").build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=4:void
                    orderingKey=8:order-42
                    debitAccountId=1:0
                    creditAccountId=1:0
                    amount=1:0
                    ledger=1:0
                    code=1:0
                    timeoutSeconds=1:0
                    reservationCommandId=6:hold-1
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("만료 2^32-1초 예약은 timeout을 int로 자르지 않고 10자리로 적는다")
        void knownVectorForTheLongestReserveTimeout() {
            // when
            LedgerCommand command = aReserveLikeTheTransfer("hold-1").timeoutSeconds(4_294_967_295L).build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=7:reserve
                    orderingKey=8:order-42
                    debitAccountId=4:1001
                    creditAccountId=4:2002
                    amount=3:500
                    ledger=1:1
                    code=2:10
                    timeoutSeconds=10:4294967295
                    reservationCommandId=0:
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("해시 필드는 명령의 모든 필드(commandId 제외)와 정확히 같다 — 새 필드가 해시에서 빠지지 않게")
        void hashedFieldsMatchTheCommandRecord() {
            // given
            List<String> commandFields = Arrays.stream(LedgerCommand.class.getRecordComponents())
                    .map(component -> component.getName())
                    .filter(name -> !name.equals("commandId"))
                    .toList();

            // then
            assertThat(ContentHash.HASHED_FIELDS).containsExactlyElementsOf(commandFields);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 기준 이체: order-42 키에서 1001 → 2002로 500, ledger 1, code 10. */
    private static LedgerCommand.Builder aTransfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10);
    }

    /** 기준 이체와 같은 값의 예약. */
    private static LedgerCommand.Builder aReserveLikeTheTransfer(String commandId) {
        return LedgerCommand.reserve(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10);
    }

    /** 기준 확정: hold-1 전액 확정. */
    private static LedgerCommand.Builder aPost() {
        return LedgerCommand.post("capture-1").orderingKey("order-42").reservation("hold-1");
    }

    /**
     * 손으로 쓴 v1 정규 텍스트의 SHA-256과 같은지 본다. 기대값은 텍스트이므로, 실패하면 어느 줄이 바뀌었는지 독자가 직접
     * 읽을 수 있다. 텍스트의 마지막 줄바꿈이 인코딩 끝의 '\n'이다.
     */
    private static void assertEncodedAs(LedgerCommand command, String canonicalText) {
        assertPinnedHash(ContentHash.of(command), sha256Hex(canonicalText));
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 고정 해시는 손으로 계산한 값이다. 다르다면 기대값을 고치지 말고 인코딩이 왜 바뀌었는지 본다. */
    private static void assertPinnedHash(String actual, String pinned) {
        assertThat(actual)
                .as("v1 인코딩이 바뀌었다. 이미 저장된 content_hash와의 비교가 깨진다 — "
                        + "의도한 변경이면 ContentHash.VERSION을 올리고 이전 버전 비교 방법을 정한다")
                .isEqualTo(pinned);
    }

    private static void assertSameHash(LedgerCommand one, LedgerCommand other) {
        assertThat(ContentHash.of(other))
                .as("같은 내용으로 인정되기(같은 해시)를 기대했다: %s / %s", one, other)
                .isEqualTo(ContentHash.of(one));
    }

    private static void assertDifferentHash(LedgerCommand one, LedgerCommand other) {
        assertThat(ContentHash.of(other))
                .as("다른 내용으로 구분되기(다른 해시)를 기대했다: %s / %s", one, other)
                .isNotEqualTo(ContentHash.of(one));
    }
}
