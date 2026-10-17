package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("다시 시도할 때까지의 대기 — 스펙 §6 worker.backoff")
class BackoffTest {

    /** 스펙 §6의 기본값. */
    static final Backoff DEFAULT = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30));

    @Nested
    @Order(1)
    @DisplayName("실패 횟수에 따라")
    class ByFailures {

        @ParameterizedTest(name = "{0}회 실패 → {1}ms", quoteTextArguments = false)
        @CsvSource(textBlock = """
                0,    200
                1,    200
                2,    400
                3,    800
                8,    25600
                9,    30000
                1000, 30000
                """)
        @DisplayName("두 배씩 늘고 상한에서 멈춘다 — 실패가 아무리 많아도 넘치지 않는다")
        void doublesUpToTheCap(long failures, long expectedMillis) {
            assertThat(DEFAULT.after(failures)).as("%d회 실패 뒤의 대기", failures)
                    .isEqualTo(Duration.ofMillis(expectedMillis));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("설정이 잘못되면")
    class WhenMisconfigured {

        @Test
        @DisplayName("처음 대기가 0이면 거부한다 — 바로 다시 잡으면 막힌 키가 워커를 붙잡는다")
        void refusesAZeroInitialWait() {
            assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("initial");
        }

        @Test
        @DisplayName("상한이 처음 대기보다 짧으면 거부한다")
        void refusesACapBelowTheInitialWait() {
            assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max");
        }
    }
}
