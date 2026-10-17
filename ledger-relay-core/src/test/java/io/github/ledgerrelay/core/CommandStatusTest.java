package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandStatus.State;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
    @DisplayName("거절 사유를 만들면")
    class MakingAReason {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                ExceedsCredits,            BUSINESS
                IdAlreadyFailed,           PRIOR_FAILURE
                DebitAccountNotFound,      INVALID
                ExistsWithDifferentAmount, CONFLICT
                LinkedEventFailed,         UNSUPPORTED
                """)
        @DisplayName("TB 코드만 주면 분류는 코드를 따라 정해진다")
        void takesItsCategoryFromTheCode(LedgerResultCode code, ResultCategory expected) {
            // when
            Rejection reason = Rejection.of(code);

            // then
            assertThat(reason).as("%s로 만든 거절 사유", code).isEqualTo(new Rejection(expected, code));
        }

        @Test
        @DisplayName("코드와 다른 분류를 붙이면 만들어지지 않는다")
        void refusesAMismatchedCategory() {
            // when
            ThrowingCallable makingIt = () -> new Rejection(ResultCategory.INVALID, LedgerResultCode.ExceedsCredits);

            // then
            assertCannotBeMade(makingIt, "ExceedsCredits");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = LedgerResultCode.class, names = {"Created", "Exists"})
        @DisplayName("적용된 결과로 만들면 만들어지지 않는다 — 적용은 거절 사유가 아니다")
        void refusesAnAppliedCode(LedgerResultCode applied) {
            // when
            ThrowingCallable makingIt = () -> Rejection.of(applied);

            // then
            assertCannotBeMade(makingIt, "거절 사유가 아닙니다");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("조회 결과를 만들면")
    class MakingAQueryResult {

        @Test
        @DisplayName("거절로 끝난 명령은 사유를 함께 담는다")
        void aRejectedCommandCarriesItsReason() {
            // when
            CommandStatus status = finished(State.REJECTED, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertThat(status.rejection()).as("거절로 끝난 명령의 사유")
                    .contains(new Rejection(ResultCategory.BUSINESS, LedgerResultCode.ExceedsCredits));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"REJECTED", "CONFLICT"})
        @DisplayName("거절·충돌인데 사유가 없으면 만들어지지 않는다")
        void refusesAMissingReason(State state) {
            // when
            ThrowingCallable makingIt = () -> finished(state, Optional.empty());

            // then
            assertCannotBeMade(makingIt, "rejection");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"APPLIED", "SKIPPED"})
        @DisplayName("적용·건너뜀에 사유를 붙이면 만들어지지 않는다")
        void refusesAReasonWhereNoneBelongs(State state) {
            // when
            ThrowingCallable makingIt = () -> finished(state, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertCannotBeMade(makingIt, "rejection");
        }

        @Test
        @DisplayName("충돌 상태에 업무 거절 사유를 붙이면 만들어지지 않는다 — 상태와 사유의 분류가 맞아야 한다")
        void refusesAReasonFromTheWrongCategory() {
            // when
            ThrowingCallable makingIt =
                    () -> finished(State.CONFLICT, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertCannotBeMade(makingIt, "ExceedsCredits");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"ACCEPTED", "IN_PROGRESS"})
        @DisplayName("끝나지 않은 명령에 완료 시각을 붙이면 만들어지지 않는다")
        void refusesACompletionTimeBeforeTheEnd(State unfinished) {
            // when
            ThrowingCallable makingIt = () -> new CommandStatus("pay-1", unfinished, Optional.empty(), BigInteger.ONE, 0,
                    ACCEPTED_AT, Optional.of(COMPLETED_AT));

            // then
            assertCannotBeMade(makingIt, "completedAt");
        }

        @Test
        @DisplayName("끝난 명령에 완료 시각이 없으면 만들어지지 않는다")
        void refusesAFinishedCommandWithoutACompletionTime() {
            // when
            ThrowingCallable makingIt = () -> new CommandStatus("pay-1", State.APPLIED, Optional.empty(), BigInteger.ONE, 1,
                    ACCEPTED_AT, Optional.empty());

            // then
            assertCannotBeMade(makingIt, "completedAt");
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static CommandStatus finished(State state, Optional<Rejection> rejection) {
        return new CommandStatus("pay-1", state, rejection, BigInteger.ONE, 1, ACCEPTED_AT, Optional.of(COMPLETED_AT));
    }

    /** 만들지 못하고 IllegalArgumentException이 나며, 메시지가 무엇이 틀렸는지 말한다. */
    private static void assertCannotBeMade(ThrowingCallable makingIt, String mentioning) {
        assertThatThrownBy(makingIt)
                .as("만들어지지 않기를 기대했다(메시지에 %s)", mentioning)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mentioning);
    }
}
