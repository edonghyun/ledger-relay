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

@DisplayName("주문 — 승인(예약) 뒤 출고 때 매입(확정), 취소는 해제 (03b §7.2)")
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
    @DisplayName("결제하면")
    class CheckingOut {

        @Test
        @DisplayName("예약이 적용되어 결제 승인됨이 되고, 구매자 돈 ฿1,490.00이 묶인다")
        void checkoutAuthorizesAndHoldsTheBuyersMoney() throws Exception {
            // given
            probe.awaitQuiet();
            long heldBefore = probe.heldBy(1001);

            // when
            ShopOrder order = shop.checkout(newKey(), 1001, "earbuds").order();

            // then
            probe.awaitState(order.id(), OrderState.AUTHORIZED);
            assertThat(probe.heldBy(1001) - heldBefore).as("구매자에게 새로 묶인 돈").isEqualTo(EARBUDS);
        }

        @Test
        @DisplayName("같은 키로 동시에 두 번 결제해도 주문 하나, 예약 명령 하나다")
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
            assertThat(first.order().id()).as("두 요청이 받은 주문").isEqualTo(second.order().id());
            assertThat(List.of(first.created(), second.created())).as("새로 만들었는가").containsExactlyInAnyOrder(true, false);
            assertThat(orders.commandsOf(first.order().id())).as("주문의 명령").extracting(OrderCommand::kind)
                    .containsExactly(CommandKind.RESERVE);
        }

        @Test
        @DisplayName("같은 키에 다른 상품으로 결제하면 거부되고, 주문은 처음 것 그대로다")
        void theSameKeyWithAnotherBodyIsRefused() {
            // given
            String key = newKey();
            ShopOrder first = shop.checkout(key, 1001, "earbuds").order();

            // then
            assertThatThrownBy(() -> shop.checkout(key, 1001, "keyboard")).as("다른 상품")
                    .isInstanceOf(IdempotencyMismatchException.class);
            assertThat(orders.find(first.id()).orElseThrow().productCode()).as("주문의 상품").isEqualTo("earbuds");
        }

        @Test
        @DisplayName("없는 상품이나 없는 구매자면 거부되고, 주문은 생기지 않는다")
        void anUnknownProductOrBuyerIsRefused() {
            // given
            String unknownProduct = newKey();
            String unknownBuyer = newKey();

            // then
            assertThatThrownBy(() -> shop.checkout(unknownProduct, 1001, "piano")).as("없는 상품")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> shop.checkout(unknownBuyer, 4242, "earbuds")).as("없는 구매자")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(orders.findByKey(unknownProduct)).as("없는 상품의 주문").isEmpty();
            assertThat(orders.findByKey(unknownBuyer)).as("없는 구매자의 주문").isEmpty();
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
    @DisplayName("결제 승인된 주문을 출고하면")
    class Shipping {

        @Test
        @DisplayName("확정이 적용되어 결제 완료가 되고, 가맹점이 ฿1,490.00을 받고 구매자 돈은 더 묶여 있지 않다")
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
            assertThat(probe.merchantReceived() - receivedBefore).as("가맹점이 새로 받은 돈").isEqualTo(EARBUDS);
            assertThat(probe.heldBy(1003)).as("구매자에게 묶인 돈").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("예약이 적용되기 전이면 출고가 거부된다")
        void shippingBeforeAuthorizationIsRefused() throws Exception {
            ledger.cutConnection();
            try {
                // given
                ShopOrder order = shop.checkout(newKey(), 1003, "earbuds").order();

                // then
                assertThatThrownBy(() -> shop.ship(order.id())).as("승인 전 출고").isInstanceOf(OrderConflictException.class);
                assertThat(orders.find(order.id()).orElseThrow().state()).as("주문 상태").isEqualTo(OrderState.ACCEPTED);
            } finally {
                ledger.restoreConnection();
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("취소하면")
    class Cancelling {

        @Test
        @DisplayName("승인된 주문을 취소하면 해제가 적용되어 취소됨이 되고, 묶였던 돈이 풀린다")
        void cancellingAnAuthorizedOrderReleasesTheHold() throws Exception {
            // given
            probe.awaitQuiet();
            long heldBefore = probe.heldBy(1004);
            ShopOrder order = authorized(1004);

            // when
            shop.cancel(order.id());

            // then
            probe.awaitState(order.id(), OrderState.CANCELLED);
            assertThat(probe.heldBy(1004)).as("구매자에게 묶인 돈").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("예약이 적용되기 전에 취소해도 예약 뒤에 해제가 나가 취소됨으로 끝난다")
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
            assertThat(orders.commandsOf(order.id())).as("주문의 명령").extracting(OrderCommand::kind)
                    .containsExactly(CommandKind.RESERVE, CommandKind.VOID);
            assertThat(probe.heldBy(1004)).as("구매자에게 묶인 돈").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("예약이 거절되기 전에 취소했으면 실패가 아니라 취소됨으로 끝난다")
        void cancellingBeforeAReservationIsRejectedEndsCancelled() throws Exception {
            // given: 잔액이 없는 구매자의 예약은 거절된다
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
            assertThat(cancelled.failureReason()).as("실패 사유").isNull();
        }

        @Test
        @DisplayName("출고를 요청한 뒤에는 취소가 거부된다 — 환불은 하지 않는다")
        void cancellingAfterShippingIsRefused() throws Exception {
            // given
            ShopOrder order = authorized(1004);
            shop.ship(order.id());

            // then
            assertThatThrownBy(() -> shop.cancel(order.id())).as("출고 뒤 취소").isInstanceOf(OrderConflictException.class);
            probe.awaitState(order.id(), OrderState.PAID);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("잔액이 모자란 구매자가 결제하면")
    class Underfunded {

        @Test
        @DisplayName("예약이 거절되어 실패가 되고, 사유는 exceeds_credits다")
        void anUnderfundedCheckoutFails() throws Exception {
            // given
            probe.drain(1005);

            // when
            ShopOrder order = shop.checkout(newKey(), 1005, "earbuds").order();

            // then
            ShopOrder failed = probe.awaitState(order.id(), OrderState.FAILED);
            assertThat(failed.failureReason()).as("실패 사유").isEqualTo("exceeds_credits");
        }
    }

    @Nested
    @Order(5)
    @DisplayName("HTTP로 부르면")
    class OverHttp {

        @Test
        @DisplayName("새 결제는 201, 같은 키로 다시 오면 200과 같은 주문이다")
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
        @DisplayName("Idempotency-Key 헤더가 없으면 400이다")
        void aMissingKeyIsABadRequest() {
            assertThat(http.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"buyerId\":1001,\"product\":\"earbuds\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("같은 키에 다른 상품이면 422, 없는 주문은 404, 상태가 맞지 않으면 409다")
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

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

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
