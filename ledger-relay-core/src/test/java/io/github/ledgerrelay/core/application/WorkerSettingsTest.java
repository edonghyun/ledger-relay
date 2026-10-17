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

@DisplayName("워커 설정 — 스펙 §6 worker.*, 03a P3a-3·P3a-8")
class WorkerSettingsTest {

    static final Duration S30 = Duration.ofSeconds(30);
    static final Duration S5 = Duration.ofSeconds(5);
    static final Duration S1 = Duration.ofSeconds(1);

    @Nested
    @Order(1)
    @DisplayName("올바르게 주면")
    class Valid {

        @Test
        @DisplayName("스펙 기본값(128, 1024, 30s, 5s)과 poll-timeout 1s로 만들어진다")
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
    @DisplayName("잘못 주면")
    class Invalid {

        @ParameterizedTest(name = "{argumentSetName} → {1}", quoteTextArguments = false)
        @MethodSource("invalidSettings")
        @DisplayName("만들 수 없고, 어느 설정이 틀렸는지 알려 준다")
        void isRefusedNamingTheSetting(ThrowingCallable making, String field) {
            assertRefusedNaming(making, field);
        }

        static Stream<Arguments> invalidSettings() {
            return Stream.of(
                    argumentSet("batch-size가 0이면",
                            (ThrowingCallable) () -> new WorkerSettings(0, 1024, S30, S5, S1), "batchSize"),
                    argumentSet("batch-size가 TB 한 요청의 최대(8189)를 넘으면",
                            (ThrowingCallable) () -> new WorkerSettings(8190, 10_000, S30, S5, S1), "batchSize"),
                    argumentSet("max-in-flight가 batch-size보다 작으면 — 슬롯을 영원히 못 잡는다",
                            (ThrowingCallable) () -> new WorkerSettings(128, 127, S30, S5, S1), "maxInFlight"),
                    argumentSet("lease가 0이면",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, Duration.ZERO, S5, S1), "lease"),
                    argumentSet("submit-timeout이 음수면",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, S30, Duration.ofSeconds(-1), S1),
                            "submitTimeout"),
                    argumentSet("poll-timeout이 0이면",
                            (ThrowingCallable) () -> new WorkerSettings(128, 1024, S30, S5, Duration.ZERO),
                            "pollTimeout"));
        }
    }

    private static void assertRefusedNaming(ThrowingCallable making, String field) {
        assertThatThrownBy(making).as("%s가 틀린 설정이 만들어지지 않기", field)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }
}
