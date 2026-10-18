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

@DisplayName("One cell of the transition table")
class TransitionTest {

    @Nested
    @Order(1)
    @DisplayName("When a cell does not match its outcome")
    class MismatchedCells {

        @Test
        @DisplayName("A move without a target state cannot be built")
        void aMoveNeedsATarget() {
            // when
            ThrowingCallable making = () -> new Transition(Outcome.MOVE, null, Set.of());

            // then
            assertRefused(making);
        }

        @Test
        @DisplayName("A non-move with a target state cannot be built")
        void aNonMoveCannotHaveATarget() {
            // when
            ThrowingCallable making = () -> new Transition(Outcome.ZERO_ROWS, StoredState.APPLIED, Set.of());

            // then
            assertRefused(making);
        }

        @Test
        @DisplayName("A non-move with effects cannot be built")
        void aNonMoveCannotHaveEffects() {
            // when
            ThrowingCallable making = () -> new Transition(Outcome.UNCHANGED, null, Set.of(Effect.RUN_HANDLER));

            // then
            assertRefused(making);
        }
    }

    private static void assertRefused(ThrowingCallable making) {
        assertThatThrownBy(making).as("cell whose outcome, target state and effects do not match is refused")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
