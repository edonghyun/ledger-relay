package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandStatus.State;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("조회 결과 — 스펙 §6 status(), 결과 반영 핸들러가 받는 값")
class CommandStatusTest {

    static final Instant ACCEPTED_AT = Instant.parse("2026-10-16T00:00:00Z");
    static final Instant COMPLETED_AT = Instant.parse("2026-10-16T00:00:05Z");

    @Nested
    @Order(1)
    @DisplayName("거절 사유는")
    class Rejections {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                ExceedsCredits,            BUSINESS
                IdAlreadyFailed,           PRIOR_FAILURE
                DebitAccountNotFound,      INVALID
                ExistsWithDifferentAmount, CONFLICT
                LinkedEventFailed,         UNSUPPORTED
                """)
        @DisplayName("TB 코드에서 분류를 따라 만든다")
        void takesItsCategoryFromTheCode(LedgerResultCode code, ResultCategory expected) {
            assertThat(Rejection.of(code)).isEqualTo(new Rejection(expected, code));
        }

        @Test
        @DisplayName("코드와 다른 분류를 붙이면 거부한다")
        void refusesAMismatchedCategory() {
            assertThatThrownBy(() -> new Rejection(ResultCategory.INVALID, LedgerResultCode.ExceedsCredits))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ExceedsCredits");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = LedgerResultCode.class, names = {"Created", "Exists"})
        @DisplayName("적용된 결과는 거절 사유가 될 수 없다")
        void refusesAnAppliedCode(LedgerResultCode applied) {
            assertThatThrownBy(() -> Rejection.of(applied))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("거절 사유가 아닙니다");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("조회 결과를 만들 때")
    class Consistency {

        @Test
        @DisplayName("거절로 끝난 명령은 사유를 함께 담는다")
        void aRejectedCommandCarriesItsReason() {
            // when
            CommandStatus status = finished(State.REJECTED, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertThat(status.rejection()).contains(new Rejection(ResultCategory.BUSINESS, LedgerResultCode.ExceedsCredits));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"REJECTED", "CONFLICT"})
        @DisplayName("거절·충돌인데 사유가 없으면 거부한다")
        void refusesAMissingReason(State state) {
            assertRefused(() -> finished(state, Optional.empty()), "rejection");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"APPLIED", "SKIPPED"})
        @DisplayName("적용·건너뜀에 사유를 붙이면 거부한다")
        void refusesAReasonWhereNoneBelongs(State state) {
            assertRefused(() -> finished(state, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits))), "rejection");
        }

        @Test
        @DisplayName("충돌 상태에 업무 거절 사유를 붙이면 거부한다 — 상태와 사유의 분류가 맞아야 한다")
        void refusesAReasonFromTheWrongCategory() {
            assertRefused(() -> finished(State.CONFLICT, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits))),
                    "ExceedsCredits");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"ACCEPTED", "IN_PROGRESS"})
        @DisplayName("끝나지 않은 명령에 완료 시각을 붙이면 거부한다")
        void refusesACompletionTimeBeforeTheEnd(State unfinished) {
            assertRefused(() -> new CommandStatus("pay-1", unfinished, Optional.empty(), BigInteger.ONE, 0,
                    ACCEPTED_AT, Optional.of(COMPLETED_AT)), "completedAt");
        }

        @Test
        @DisplayName("끝난 명령에 완료 시각이 없으면 거부한다")
        void refusesAFinishedCommandWithoutACompletionTime() {
            assertRefused(() -> new CommandStatus("pay-1", State.APPLIED, Optional.empty(), BigInteger.ONE, 1,
                    ACCEPTED_AT, Optional.empty()), "completedAt");
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static CommandStatus finished(State state, Optional<Rejection> rejection) {
        return new CommandStatus("pay-1", state, rejection, BigInteger.ONE, 1, ACCEPTED_AT, Optional.of(COMPLETED_AT));
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable building, String mentioning) {
        assertThatThrownBy(building)
                .as("이 조회 결과는 만들 수 없어야 한다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mentioning);
    }
}
