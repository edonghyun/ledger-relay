package io.github.ledgerrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.SkipReason;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Command row")
class CommandRowTest {

    @Nested
    @Order(1)
    @DisplayName("When a row is built with a skip reason")
    class WithASkipReason {

        @Test
        @DisplayName("A skipped row without a reason cannot be built — a store that drops the reason is caught on the spot")
        void refusesASkippedRowWithoutAReason() {
            // when
            ThrowingCallable makingIt = () -> new CommandRow(StoredState.SKIPPED, 1, 0, Optional.empty(), Optional.empty(), 0);

            // then
            assertCannotBeMade(makingIt);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"PENDING", "HALTED", "APPLIED"})
        @DisplayName("A row that is not skipped cannot be built with a reason")
        void refusesAReasonOnARowThatWasNotSkipped(StoredState state) {
            // when
            ThrowingCallable makingIt = () -> new CommandRow(state, 1, 0, Optional.empty(), Optional.empty(), 0,
                    Optional.of(SkipReason.ABANDONED_BY_OPERATOR));

            // then
            assertCannotBeMade(makingIt);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    private static void assertCannotBeMade(ThrowingCallable makingIt) {
        assertThatThrownBy(makingIt)
                .as("expected the row to be refused because the skip reason does not match the state")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipReason");
    }
}
