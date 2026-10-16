package io.github.ledgerrelay.core;

import static io.github.ledgerrelay.core.LedgerCommand.AMOUNT_MAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("내용 해시 — 스펙 §5.1 정규화, G7 내용 무결성")
class ContentHashTest {

    @Nested
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
    @DisplayName("내용이 하나라도 다르면")
    class DifferentContent {

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("oneFieldApart")
        @DisplayName("해시가 다르다")
        void hashDiffers(LedgerCommand one, LedgerCommand other) {
            assertDifferentHash(one, other);
        }

        static Stream<Arguments> oneFieldApart() {
            return Stream.of(
                    argumentSet("kind가 다르면 (이체 vs 예약)",
                            aTransfer("c").build(), aReserveLikeTheTransfer("c").build()),
                    argumentSet("orderingKey가 다르면",
                            aTransfer("c").build(), aTransfer("c").orderingKey("order-43").build()),
                    argumentSet("보내는 계좌가 다르면",
                            aTransfer("c").build(), aTransfer("c").debit(1003).build()),
                    argumentSet("받는 계좌가 다르면",
                            aTransfer("c").build(), aTransfer("c").credit(2003).build()),
                    argumentSet("금액이 다르면",
                            aTransfer("c").build(), aTransfer("c").amount(501).build()),
                    argumentSet("ledger가 다르면",
                            aTransfer("c").build(), aTransfer("c").ledger(2).build()),
                    argumentSet("code가 다르면",
                            aTransfer("c").build(), aTransfer("c").code(11).build()),
                    argumentSet("timeoutSeconds가 다르면",
                            aReserveLikeTheTransfer("c").timeoutSeconds(30).build(),
                            aReserveLikeTheTransfer("c").timeoutSeconds(31).build()),
                    argumentSet("reservation이 다르면",
                            aPost().build(), aPost().reservation("hold-2").build()),
                    argumentSet("user_data_64가 다르면",
                            aTransfer("c").build(), aTransfer("c").userData64(7).build()),
                    argumentSet("user_data_32가 다르면",
                            aTransfer("c").build(), aTransfer("c").userData32(7).build()));
        }

        @Test
        @DisplayName("위 표는 해시 필드를 하나도 빠뜨리지 않는다")
        void tableCoversEveryHashedField() {
            // 위 표의 행 이름 앞부분(필드)과 HASHED_FIELDS가 1:1로 대응하는지 사람이 읽기 쉽게 명시한다.
            List<String> coveredByTheTable = List.of("kind", "orderingKey", "debitAccountId", "creditAccountId",
                    "amount", "ledger", "code", "timeoutSeconds", "reservationCommandId", "userData64", "userData32");

            assertThat(ContentHash.HASHED_FIELDS).containsExactlyInAnyOrderElementsOf(coveredByTheTable);
        }
    }

    @Nested
    @DisplayName("해시는 DB에 저장되므로 v1 인코딩은 고정이다 — 바꾸려면 VERSION을 올린다")
    class FixedEncoding {

        @Test
        @DisplayName("기준 이체(order-42, 1001 → 2002, 500, ledger 1, code 10)의 해시는 13ff1a83…다")
        void knownVectorForTheReferenceTransfer() {
            // when
            String hash = ContentHash.of(aTransfer("pay-1").build());

            // then
            assertThat(hash).isEqualTo("13ff1a83e735898e91f4e1f133b9818de72293f991856e926a0f4f8021c85b09");
        }

        @Test
        @DisplayName("user_data는 부호 없는 정수로 해시한다: -1은 2^64-1과 2^32-1로 읽힌다")
        void userDataIsHashedUnsigned() {
            // when
            String hash = ContentHash.of(aTransfer("pay-1").userData64(-1L).userData32(-1).build());

            // then
            assertThat(hash).isEqualTo("bbda13fdbd0aec09cb1ae90ab5373ec19c10aced23daabdd833020da9c1d51e8");
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
