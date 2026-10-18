package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.time.Duration;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Worker settings — worker.*")
class WorkerSettingsTest {

    static final Duration S30 = Duration.ofSeconds(30);
    static final Duration S5 = Duration.ofSeconds(5);
    static final Duration S1 = Duration.ofSeconds(1);

    @Nested
    @Order(1)
    @DisplayName("When given valid values")
    class Valid {

        @Test
        @DisplayName("It is built with the defaults (128, 1024, 30s, 5s) and poll-timeout 1s")
        void theSpecDefaultsAreAccepted() {
            // when
            WorkerSettings settings = new WorkerSettings(128, 1024, S30, S5, S1);

            // then
            assertThat(settings.batchSize()).as("batch-size").isEqualTo(128);
            assertThat(settings.maxInFlight()).as("max-in-flight").isEqualTo(1024);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When given invalid values")
    class Invalid {

        @ParameterizedTest(name = "{argumentSetName} → {1}", quoteTextArguments = false)
        @MethodSource("invalidSettings")
        @DisplayName("It cannot be built, and the error names the wrong setting")
        void isRefusedNamingTheSetting(ThrowingCallable making, String field) {
            assertRefusedNaming(making, field);
        }

        static Stream<Arguments> invalidSettings() {
            return Stream.of(
                    argumentSet("batch-size 0",
                            (ThrowingCallable) () -> new WorkerSettings(0, 1024, S30, S5, S1), "batchSize"),
                    argumentSet("batch-size above the TB maximum per request (8189)",
                            (ThrowingCallable) () -> new WorkerSettings(8190, 10_000, S30, S5, S1), "batchSize"),
                    argumentSet("max-in-flight smaller than batch-size — slots can never be taken",
                            (ThrowingCallable) () -> new WorkerSettings(128, 127, S30, S5, S1), "maxInFlight"),
                    argumentSet("lease 0",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, Duration.ZERO, S5, S1), "lease"),
                    argumentSet("a negative submit-timeout",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, S30, Duration.ofSeconds(-1), S1),
                            "submitTimeout"),
                    argumentSet("lease shorter than twice submit-timeout — another worker claims the same command again during the wait",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, Duration.ofSeconds(9), S5, S1), "lease"),
                    argumentSet("poll-timeout 0",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, S30, S5, Duration.ZERO),
                            "pollTimeout"));
        }
    }

    private static void assertRefusedNaming(ThrowingCallable making, String field) {
        assertThatThrownBy(making).as("settings with a wrong %s are refused", field)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }
}
