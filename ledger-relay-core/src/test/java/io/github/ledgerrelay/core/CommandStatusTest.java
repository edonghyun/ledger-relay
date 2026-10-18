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

@DisplayName("Query result — the value status() returns and the outcome handler receives")
class CommandStatusTest {

    static final Instant ACCEPTED_AT = Instant.parse("2026-10-16T00:00:00Z");
    static final Instant COMPLETED_AT = Instant.parse("2026-10-16T00:00:05Z");

    @Nested
    @Order(1)
    @DisplayName("When a rejection reason is built")
    class MakingAReason {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                ExceedsCredits,            BUSINESS
                IdAlreadyFailed,           PRIOR_FAILURE
                DebitAccountNotFound,      INVALID
                ExistsWithDifferentAmount, CONFLICT
                LinkedEventFailed,         UNSUPPORTED
                """)
        @DisplayName("Given only a TB code, the category follows from the code")
        void takesItsCategoryFromTheCode(LedgerResultCode code, ResultCategory expected) {
            // when
            Rejection reason = Rejection.of(code);

            // then
            assertThat(reason).as("rejection reason built from %s", code).isEqualTo(new Rejection(expected, code));
        }

        @Test
        @DisplayName("A category that does not match the code cannot be attached")
        void refusesAMismatchedCategory() {
            // when
            ThrowingCallable makingIt = () -> new Rejection(ResultCategory.INVALID, LedgerResultCode.ExceedsCredits);

            // then
            assertCannotBeMade(makingIt, "ExceedsCredits");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = LedgerResultCode.class, names = {"Created", "Exists"})
        @DisplayName("An applied result cannot be turned into one — being applied is not a rejection reason")
        void refusesAnAppliedCode(LedgerResultCode applied) {
            // when
            ThrowingCallable makingIt = () -> Rejection.of(applied);

            // then
            assertCannotBeMade(makingIt, "is not a rejection reason");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a query result is built")
    class MakingAQueryResult {

        @Test
        @DisplayName("A command that ended rejected carries its reason")
        void aRejectedCommandCarriesItsReason() {
            // when
            CommandStatus status = finished(State.REJECTED, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertThat(status.rejection()).as("reason of the rejected command")
                    .contains(new Rejection(ResultCategory.BUSINESS, LedgerResultCode.ExceedsCredits));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"REJECTED", "CONFLICT"})
        @DisplayName("A rejected or conflicting result without a reason cannot be built")
        void refusesAMissingReason(State state) {
            // when
            ThrowingCallable makingIt = () -> finished(state, Optional.empty());

            // then
            assertCannotBeMade(makingIt, "rejection");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"APPLIED", "SKIPPED"})
        @DisplayName("An applied or skipped result with a reason cannot be built")
        void refusesAReasonWhereNoneBelongs(State state) {
            // when
            ThrowingCallable makingIt = () -> finished(state, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertCannotBeMade(makingIt, "rejection");
        }

        @Test
        @DisplayName("A conflict state with a business rejection reason cannot be built — the state and the reason's category must match")
        void refusesAReasonFromTheWrongCategory() {
            // when
            ThrowingCallable makingIt =
                    () -> finished(State.CONFLICT, Optional.of(Rejection.of(LedgerResultCode.ExceedsCredits)));

            // then
            assertCannotBeMade(makingIt, "ExceedsCredits");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"ACCEPTED", "IN_PROGRESS", "HALTED"})
        @DisplayName("A command that is unfinished or halted cannot be built with a completion time")
        void refusesACompletionTimeBeforeTheEnd(State unfinished) {
            // when
            ThrowingCallable makingIt = () -> new CommandStatus("pay-1", unfinished, Optional.empty(), Optional.empty(), BigInteger.ONE, 0,
                    ACCEPTED_AT, Optional.of(COMPLETED_AT));

            // then
            assertCannotBeMade(makingIt, "completedAt");
        }

        @Test
        @DisplayName("A finished command without a completion time cannot be built")
        void refusesAFinishedCommandWithoutACompletionTime() {
            // when
            ThrowingCallable makingIt = () -> new CommandStatus("pay-1", State.APPLIED, Optional.empty(), Optional.empty(), BigInteger.ONE, 1,
                    ACCEPTED_AT, Optional.empty());

            // then
            assertCannotBeMade(makingIt, "completedAt");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When a query result that ended skipped is built")
    class MakingASkippedResult {

        @ParameterizedTest(name = "{0}")
        @EnumSource(SkipReason.class)
        @DisplayName("It carries why it was skipped — so the app can tell an unapplied reservation from an operator abandon")
        void aSkippedCommandCarriesWhyItWasSkipped(SkipReason why) {
            // when
            CommandStatus status = skipped(Optional.of(why));

            // then
            assertThat(status.skipReason()).as("skip reason").contains(why);
        }

        @Test
        @DisplayName("Without a reason it cannot be built")
        void refusesAMissingSkipReason() {
            // when
            ThrowingCallable makingIt = () -> skipped(Optional.empty());

            // then
            assertCannotBeMade(makingIt, "skipReason");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = State.class, names = {"APPLIED", "ACCEPTED", "HALTED"})
        @DisplayName("A result that is not skipped cannot be built with a reason")
        void refusesASkipReasonWhereNoneBelongs(State state) {
            // when
            ThrowingCallable makingIt = () -> new CommandStatus("pay-1", state, Optional.empty(),
                    Optional.of(SkipReason.ABANDONED_BY_OPERATOR), BigInteger.ONE, 0, ACCEPTED_AT,
                    state.isFinal() ? Optional.of(COMPLETED_AT) : Optional.empty());

            // then
            assertCannotBeMade(makingIt, "skipReason");
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    private static CommandStatus skipped(Optional<SkipReason> why) {
        return new CommandStatus("pay-1", State.SKIPPED, Optional.empty(), why, BigInteger.ONE, 0, ACCEPTED_AT,
                Optional.of(COMPLETED_AT));
    }

    private static CommandStatus finished(State state, Optional<Rejection> rejection) {
        return new CommandStatus("pay-1", state, rejection, Optional.empty(), BigInteger.ONE, 1, ACCEPTED_AT,
                Optional.of(COMPLETED_AT));
    }

    /** Building fails with IllegalArgumentException, and the message says what was wrong. */
    private static void assertCannotBeMade(ThrowingCallable makingIt, String mentioning) {
        assertThatThrownBy(makingIt)
                .as("expected building to fail (message mentions %s)", mentioning)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(mentioning);
    }
}
