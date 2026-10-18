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

@DisplayName("다시 시도할 때까지의 대기 — worker.backoff")
class BackoffTest {

    /** 기본값. */
    static final Backoff DEFAULT = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30));

    @Nested
    @Order(1)
    @DisplayName("실패가 거듭되면")
    class AsFailuresAddUp {

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
            // when
            Duration wait = DEFAULT.after(failures);

            // then
            assertThat(wait).as("%d회 실패 뒤의 대기", failures).isEqualTo(Duration.ofMillis(expectedMillis));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("설정이 잘못되면")
    class WhenMisconfigured {

        @Test
        @DisplayName("처음 대기가 0이면 거부한다 — 바로 다시 잡으면 막힌 키가 워커를 붙잡는다")
        void refusesAZeroInitialWait() {
            // when
            ThrowingCallable configuring = () -> new Backoff(Duration.ZERO, Duration.ofSeconds(30));

            // then
            assertRefusedAsMisconfigured(configuring, "initial");
        }

        @Test
        @DisplayName("상한이 처음 대기보다 짧으면 거부한다")
        void refusesACapBelowTheInitialWait() {
            // when
            ThrowingCallable configuring = () -> new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(1));

            // then
            assertRefusedAsMisconfigured(configuring, "max");
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static void assertRefusedAsMisconfigured(ThrowingCallable configuring, String setting) {
        assertThatThrownBy(configuring)
                .as("%s 설정이 잘못되어 거부되기를 기대했다", setting)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(setting);
    }
}
