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

@DisplayName("명령")
class CommandRowTest {

    @Nested
    @Order(1)
    @DisplayName("건너뛴 이유를 담아 행을 만들면")
    class WithASkipReason {

        @Test
        @DisplayName("건너뜀인데 이유가 없으면 만들어지지 않는다 — 저장소가 이유를 빠뜨린 행을 그 자리에서 잡는다")
        void refusesASkippedRowWithoutAReason() {
            // when
            ThrowingCallable makingIt = () -> new CommandRow(StoredState.SKIPPED, 1, 0, Optional.empty(), Optional.empty(), 0);

            // then
            assertCannotBeMade(makingIt);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"PENDING", "HALTED", "APPLIED"})
        @DisplayName("건너뜀이 아닌데 이유가 있으면 만들어지지 않는다")
        void refusesAReasonOnARowThatWasNotSkipped(StoredState state) {
            // when
            ThrowingCallable makingIt = () -> new CommandRow(state, 1, 0, Optional.empty(), Optional.empty(), 0,
                    Optional.of(SkipReason.ABANDONED_BY_OPERATOR));

            // then
            assertCannotBeMade(makingIt);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static void assertCannotBeMade(ThrowingCallable makingIt) {
        assertThatThrownBy(makingIt)
                .as("건너뛴 이유가 상태와 맞지 않아 만들어지지 않기를 기대했다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipReason");
    }
}
