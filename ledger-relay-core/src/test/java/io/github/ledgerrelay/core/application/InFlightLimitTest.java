package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("In-flight slots")
class InFlightLimitTest {

    @Nested
    @Order(1)
    @DisplayName("When slots are left")
    class Enough {

        @Test
        @DisplayName("They are taken at once, and the remaining slots drop by that many")
        void takesAtOnce() {
            // given
            InFlightLimit slots = new InFlightLimit(4);

            // when
            boolean taken = slots.tryTake(3, Duration.ZERO);

            // then
            assertThat(taken).as("taken").isTrue();
            assertThat(slots.available()).as("remaining slots").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("It remembers how many slots it was built with — so the worker can check it matches the settings")
    void capacityIsTheSlotCountGiven() {
        // when
        InFlightLimit slots = new InFlightLimit(64);

        // then
        assertThat(slots.capacity()).as("slot count").isEqualTo(64);
    }

    @Nested
    @Order(2)
    @DisplayName("When there are not enough slots")
    class NotEnough {

        @Test
        @DisplayName("After the wait limit it answers that it could not take them, and it takes none partially")
        void takesNothingPartially() {
            // given
            InFlightLimit slots = new InFlightLimit(2);

            // when
            boolean taken = slots.tryTake(3, Duration.ofMillis(100));

            // then
            assertThat(taken).as("taken").isFalse();
            assertThat(slots.available()).as("remaining slots").isEqualTo(2);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When another side gives slots back during the wait")
    class GivenBackWhileWaiting {

        @Test
        @DisplayName("The waiting side takes them")
        void theWaiterTakesThem() throws Exception {
            // given: another side holds both of the 2 slots, and this side waits for 2
            InFlightLimit slots = new InFlightLimit(2);
            slots.tryTake(2, Duration.ZERO);
            CompletableFuture<Boolean> waiting =
                    CompletableFuture.supplyAsync(() -> slots.tryTake(2, Duration.ofSeconds(10)));

            // when
            slots.giveBack(2);

            // then
            assertThat(waiting.get(10, TimeUnit.SECONDS)).as("whether the waiting side took them").isTrue();
        }
    }
}
