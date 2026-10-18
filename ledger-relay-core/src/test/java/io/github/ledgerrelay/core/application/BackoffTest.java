package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Wait before the next attempt — worker.backoff")
class BackoffTest {

    /** The defaults. */
    static final Backoff DEFAULT = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30));

    @Nested
    @Order(1)
    @DisplayName("As failures add up")
    class AsFailuresAddUp {

        @ParameterizedTest(name = "{0} failures → {1}ms", quoteTextArguments = false)
        @CsvSource(textBlock = """
                0,    200
                1,    200
                2,    400
                3,    800
                8,    25600
                9,    30000
                1000, 30000
                """)
        @DisplayName("It doubles and stops at the cap — it does not overflow however many failures there are")
        void doublesUpToTheCap(long failures, long expectedMillis) {
            // when
            Duration wait = DEFAULT.after(failures);

            // then
            assertThat(wait).as("wait after %d failures", failures).isEqualTo(Duration.ofMillis(expectedMillis));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When it is misconfigured")
    class WhenMisconfigured {

        @Test
        @DisplayName("An initial wait of 0 is refused — a blocked key that is claimed again at once ties up the worker")
        void refusesAZeroInitialWait() {
            // when
            ThrowingCallable configuring = () -> new Backoff(Duration.ZERO, Duration.ofSeconds(30));

            // then
            assertRefusedAsMisconfigured(configuring, "initial");
        }

        @Test
        @DisplayName("A cap shorter than the initial wait is refused")
        void refusesACapBelowTheInitialWait() {
            // when
            ThrowingCallable configuring = () -> new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(1));

            // then
            assertRefusedAsMisconfigured(configuring, "max");
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    private static void assertRefusedAsMisconfigured(ThrowingCallable configuring, String setting) {
        assertThatThrownBy(configuring)
                .as("expected refusal because the %s setting is wrong", setting)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(setting);
    }
}
