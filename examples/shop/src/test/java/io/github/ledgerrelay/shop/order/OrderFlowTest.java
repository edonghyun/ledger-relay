package io.github.ledgerrelay.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigerbeetle.Client;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.shop.ShopProbe;
import io.github.ledgerrelay.shop.ShopSpringTest;
import io.github.ledgerrelay.shop.ShopTestEnvironment;
import io.github.ledgerrelay.tigerbeetle.testing.ProxiedTigerBeetle;
import java.util.List;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@DisplayName("Orders — authorize (reserve), capture (post) on shipping, cancel voids")
class OrderFlowTest extends ShopSpringTest {

    static final long EARBUDS = 149_000;

    @Autowired
    OrderService shop;
    @Autowired
    Orders orders;
    @Autowired
    Client client;
    @Autowired
    MockMvcTester http;
    @Autowired
    DataSource dataSource;

    final ProxiedTigerBeetle ledger = ShopTestEnvironment.shared().ledger();
    ShopProbe probe;

    @BeforeEach
    void probe() {
        probe = new ShopProbe(orders, client, dataSource);
    }

    @Nested
    @Order(1)
    @DisplayName("When the buyer pays")
    class CheckingOut {

        @Test
        @DisplayName("The reservation is applied, the order becomes authorized and ฿1,490.00 of the buyer's money is held")
        void checkoutAuthorizesAndHoldsTheBuyersMoney() throws Exception {
            // given
            probe.awaitQuiet();
            long heldBefore = probe.heldBy(1001);

            // when
            ShopOrder order = shop.checkout(newKey(), 1001, "earbuds").order();

            // then
            probe.awaitState(order.id(), OrderState.AUTHORIZED);
            assertThat(probe.heldBy(1001) - heldBefore).as("newly held buyer money").isEqualTo(EARBUDS);
        }

        @Test
        @DisplayName("Paying twice at the same time with the same key gives one order and one reservation command")
        void aDoubleClickMakesOneOrderAndOneReservation() throws Exception {
            // given
            String key = newKey();
            CountDownLatch together = new CountDownLatch(1);
            List<CompletableFuture<Checkout>> clicks = List.of(click(together, key), click(together, key));

            // when
            together.countDown();
            Checkout first = clicks.get(0).get(10, TimeUnit.SECONDS);
            Checkout second = clicks.get(1).get(10, TimeUnit.SECONDS);

            // then
            assertThat(first.order().id()).as("order returned to both requests").isEqualTo(second.order().id());
            assertThat(List.of(first.created(), second.created())).as("created flags").containsExactlyInAnyOrder(true, false);
            assertThat(orders.commandsOf(first.order().id())).as("commands of the order").extracting(OrderCommand::kind)
                    .containsExactly(CommandKind.RESERVE);
        }

        @Test
        @DisplayName("Paying for another product with the same key is refused and the original order stays unchanged")
        void theSameKeyWithAnotherBodyIsRefused() {
            // given
            String key = newKey();
            ShopOrder first = shop.checkout(key, 1001, "earbuds").order();

            // then
            assertThatThrownBy(() -> shop.checkout(key, 1001, "keyboard")).as("other product")
                    .isInstanceOf(IdempotencyMismatchException.class);
            assertThat(orders.find(first.id()).orElseThrow().productCode()).as("product of the order").isEqualTo("earbuds");
        }

        @Test
        @DisplayName("An unknown product or buyer is refused and no order is created")
        void anUnknownProductOrBuyerIsRefused() {
            // given
            String unknownProduct = newKey();
            String unknownBuyer = newKey();

            // then
            assertThatThrownBy(() -> shop.checkout(unknownProduct, 1001, "piano")).as("unknown product")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> shop.checkout(unknownBuyer, 4242, "earbuds")).as("unknown buyer")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(orders.findByKey(unknownProduct)).as("order for the unknown product").isEmpty();
            assertThat(orders.findByKey(unknownBuyer)).as("order for the unknown buyer").isEmpty();
        }

        private CompletableFuture<Checkout> click(CountDownLatch together, String key) {
            return CompletableFuture.supplyAsync(() -> {
                awaitQuietly(together);
                return shop.checkout(key, 1002, "earbuds");
            });
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When an authorized order is shipped")
    class Shipping {

        @Test
        @DisplayName("The post is applied, the order becomes paid, the merchant receives ฿1,490.00 and no buyer money stays held")
        void shippingCapturesTheMoney() throws Exception {
            // given
            probe.awaitQuiet();
            long heldBefore = probe.heldBy(1003);
            long receivedBefore = probe.merchantReceived();
            ShopOrder order = authorized(1003);

            // when
            shop.ship(order.id());

            // then
            probe.awaitState(order.id(), OrderState.PAID);
            assertThat(probe.merchantReceived() - receivedBefore).as("money newly received by the merchant").isEqualTo(EARBUDS);
            assertThat(probe.heldBy(1003)).as("buyer money held").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("Shipping before the reservation is applied is refused")
        void shippingBeforeAuthorizationIsRefused() throws Exception {
            ledger.cutConnection();
            try {
                // given
                ShopOrder order = shop.checkout(newKey(), 1003, "earbuds").order();

                // then
                assertThatThrownBy(() -> shop.ship(order.id())).as("shipping before authorization").isInstanceOf(OrderConflictException.class);
                assertThat(orders.find(order.id()).orElseThrow().state()).as("order state").isEqualTo(OrderState.ACCEPTED);
            } finally {
                ledger.restoreConnection();
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When the order is cancelled")
    class Cancelling {

        @Test
        @DisplayName("Cancelling an authorized order applies the void, the order becomes cancelled and the held money is released")
        void cancellingAnAuthorizedOrderReleasesTheHold() throws Exception {
            // given
            probe.awaitQuiet();
            long heldBefore = probe.heldBy(1004);
            ShopOrder order = authorized(1004);

            // when
            shop.cancel(order.id());

            // then
            probe.awaitState(order.id(), OrderState.CANCELLED);
            assertThat(probe.heldBy(1004)).as("buyer money held").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("Cancelling before the reservation is applied sends the void after the reservation and ends cancelled")
        void cancellingBeforeAuthorizationStillEndsCancelled() throws Exception {
            // given
            probe.awaitQuiet();
            long heldBefore = probe.heldBy(1004);
            ShopOrder order;
            ledger.cutConnection();
            try {
                order = shop.checkout(newKey(), 1004, "earbuds").order();

                // when
                shop.cancel(order.id());
            } finally {
                ledger.restoreConnection();
            }

            // then
            probe.awaitState(order.id(), OrderState.CANCELLED);
            assertThat(orders.commandsOf(order.id())).as("commands of the order").extracting(OrderCommand::kind)
                    .containsExactly(CommandKind.RESERVE, CommandKind.VOID);
            assertThat(probe.heldBy(1004)).as("buyer money held").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("Cancelling before the reservation is rejected ends cancelled, not failed")
        void cancellingBeforeAReservationIsRejectedEndsCancelled() throws Exception {
            // given: the reservation of a buyer with no balance is rejected
            probe.drain(1005);
            ShopOrder order;
            ledger.cutConnection();
            try {
                order = shop.checkout(newKey(), 1005, "earbuds").order();

                // when
                shop.cancel(order.id());
            } finally {
                ledger.restoreConnection();
            }

            // then
            ShopOrder cancelled = probe.awaitState(order.id(), OrderState.CANCELLED);
            assertThat(cancelled.failureReason()).as("failure reason").isNull();
        }

        @Test
        @DisplayName("Cancelling after shipping was requested is refused — there are no refunds")
        void cancellingAfterShippingIsRefused() throws Exception {
            // given
            ShopOrder order = authorized(1004);
            shop.ship(order.id());

            // then
            assertThatThrownBy(() -> shop.cancel(order.id())).as("cancel after shipping").isInstanceOf(OrderConflictException.class);
            probe.awaitState(order.id(), OrderState.PAID);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When a buyer with insufficient funds pays")
    class Underfunded {

        @Test
        @DisplayName("The reservation is rejected and the order fails with reason exceeds_credits")
        void anUnderfundedCheckoutFails() throws Exception {
            // given
            probe.drain(1005);

            // when
            ShopOrder order = shop.checkout(newKey(), 1005, "earbuds").order();

            // then
            ShopOrder failed = probe.awaitState(order.id(), OrderState.FAILED);
            assertThat(failed.failureReason()).as("failure reason").isEqualTo("exceeds_credits");
        }
    }

    @Nested
    @Order(5)
    @DisplayName("Over HTTP")
    class OverHttp {

        @Test
        @DisplayName("A new payment answers 201, a repeat with the same key answers 200 with the same order")
        void aRepeatedRequestReturnsTheSameOrder() {
            // given
            String key = newKey();

            // when
            MvcTestResult first = checkout(key, "earbuds");
            MvcTestResult again = checkout(key, "earbuds");

            // then
            assertThat(first).hasStatus(HttpStatus.CREATED);
            assertThat(again).hasStatus(HttpStatus.OK);
            long id = orders.findByKey(key).orElseThrow().id();
            assertThat(first).bodyJson().extractingPath("$.id").asNumber().isEqualTo((int) id);
            assertThat(again).bodyJson().extractingPath("$.id").asNumber().isEqualTo((int) id);
        }

        @Test
        @DisplayName("A request without the Idempotency-Key header answers 400")
        void aMissingKeyIsABadRequest() {
            assertThat(http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"buyerId\":1001,\"product\":\"earbuds\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Another product under the same key answers 422, an unknown order 404, a wrong state 409")
        void refusalsHaveTheirOwnStatus() {
            // given
            String key = newKey();
            checkout(key, "earbuds");
            ShopOrder cancelled = shop.cancel(shop.checkout(newKey(), 1001, "mouse").order().id());

            // then
            assertThat(checkout(key, "keyboard")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(http.post().uri("/api/orders/{id}/ship", Long.MAX_VALUE)).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(http.post().uri("/api/orders/{id}/ship", cancelled.id())).hasStatus(HttpStatus.CONFLICT);
        }

        private MvcTestResult checkout(String key, String product) {
            return http.post().uri("/api/orders").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"buyerId\":1001,\"product\":\"" + product + "\"}").exchange();
        }
    }

    // ── Helpers used only in this file ───────────────────────────────────

    private ShopOrder authorized(long buyer) throws InterruptedException {
        ShopOrder order = shop.checkout(newKey(), buyer, "earbuds").order();
        return probe.awaitState(order.id(), OrderState.AUTHORIZED);
    }

    private static String newKey() {
        return "test-" + UUID.randomUUID();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
