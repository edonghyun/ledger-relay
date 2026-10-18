package io.github.ledgerrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.domain.Transition.Effect;
import io.github.ledgerrelay.core.domain.Transition.Outcome;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("전이표 한 칸")
class TransitionTest {

    @Nested
    @Order(1)
    @DisplayName("결과와 맞지 않는 칸을 만들면")
    class MismatchedCells {

        @Test
        @DisplayName("이동인데 목적 상태가 없으면 만들 수 없다")
        void aMoveNeedsATarget() {
            // when
            ThrowingCallable making = () -> new Transition(Outcome.MOVE, null, Set.of());

            // then
            assertRefused(making);
        }

        @Test
        @DisplayName("이동이 아닌데 목적 상태가 있으면 만들 수 없다")
        void aNonMoveCannotHaveATarget() {
            // when
            ThrowingCallable making = () -> new Transition(Outcome.ZERO_ROWS, StoredState.APPLIED, Set.of());

            // then
            assertRefused(making);
        }

        @Test
        @DisplayName("이동이 아닌데 효과가 있으면 만들 수 없다")
        void aNonMoveCannotHaveEffects() {
            // when
            ThrowingCallable making = () -> new Transition(Outcome.UNCHANGED, null, Set.of(Effect.RUN_HANDLER));

            // then
            assertRefused(making);
        }
    }

    private static void assertRefused(ThrowingCallable making) {
        assertThatThrownBy(making).as("결과·목적 상태·효과가 맞지 않는 칸이 만들어지지 않기")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
