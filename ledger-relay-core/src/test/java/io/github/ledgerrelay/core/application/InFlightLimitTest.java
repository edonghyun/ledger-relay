package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("in-flight 슬롯 — 스펙 §5.3-2, 03a P3a-2")
class InFlightLimitTest {

    @Nested
    @Order(1)
    @DisplayName("슬롯이 남아 있으면")
    class Enough {

        @Test
        @DisplayName("바로 잡히고, 남은 슬롯이 그만큼 준다")
        void takesAtOnce() {
            // given
            InFlightLimit slots = new InFlightLimit(4);

            // when
            boolean taken = slots.tryTake(3, Duration.ZERO);

            // then
            assertThat(taken).as("잡혔는가").isTrue();
            assertThat(slots.available()).as("남은 슬롯").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("만든 슬롯 수를 기억한다 — 워커가 설정과 맞는지 확인할 수 있게 (02d I5)")
    void capacityIsTheSlotCountGiven() {
        // when
        InFlightLimit slots = new InFlightLimit(64);

        // then
        assertThat(slots.capacity()).as("슬롯 수").isEqualTo(64);
    }

    @Nested
    @Order(2)
    @DisplayName("슬롯이 모자라면")
    class NotEnough {

        @Test
        @DisplayName("기다림 상한 뒤 못 잡았다고 답하고, 일부만 잡지 않는다")
        void takesNothingPartially() {
            // given
            InFlightLimit slots = new InFlightLimit(2);

            // when
            boolean taken = slots.tryTake(3, Duration.ofMillis(100));

            // then
            assertThat(taken).as("잡혔는가").isFalse();
            assertThat(slots.available()).as("남은 슬롯").isEqualTo(2);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("기다리는 동안 다른 쪽이 돌려주면")
    class GivenBackWhileWaiting {

        @Test
        @DisplayName("기다리던 쪽이 잡는다")
        void theWaiterTakesThem() throws Exception {
            // given: 슬롯 2개를 다른 쪽이 모두 쥐었고, 이쪽은 2개를 기다린다
            InFlightLimit slots = new InFlightLimit(2);
            slots.tryTake(2, Duration.ZERO);
            CompletableFuture<Boolean> waiting =
                    CompletableFuture.supplyAsync(() -> slots.tryTake(2, Duration.ofSeconds(10)));

            // when
            slots.giveBack(2);

            // then
            assertThat(waiting.get(10, TimeUnit.SECONDS)).as("기다리던 쪽이 잡았는가").isTrue();
        }
    }
}
