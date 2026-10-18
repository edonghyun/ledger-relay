package io.github.ledgerrelay.shop.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.tigerbeetle.Client;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.shop.ShopProbe;
import io.github.ledgerrelay.shop.ShopSpringTest;
import io.github.ledgerrelay.shop.order.OrderCommand;
import io.github.ledgerrelay.shop.order.OrderService;
import io.github.ledgerrelay.shop.order.OrderState;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@ActiveProfiles("demo")
@DisplayName("데모 조작 — 장애를 일부러 내고 운영자가 수습한다 (03b §7.3, §7.4)")
class DemoScenarioTest extends ShopSpringTest {

    @Autowired
    MockMvcTester http;
    @Autowired
    OrderService shop;
    @Autowired
    Orders orders;
    @Autowired
    Client client;
    @Autowired
    DataSource dataSource;

    ShopProbe probe;

    @BeforeEach
    void probe() {
        probe = new ShopProbe(orders, client, dataSource);
    }

    @Nested
    @Order(1)
    @DisplayName("TB 연결을 끊으면")
    class LedgerOutage {

        @Test
        @DisplayName("결제는 TB를 기다리지 않고 1초 안에 접수되고, 다시 이으면 결제 승인됨이 된다")
        void checkoutDoesNotWaitForTheLedger() throws Exception {
            // given
            assertThat(http.post().uri("/api/demo/ledger/cut")).hasStatus(HttpStatus.OK);
            List<Long> accepted = new ArrayList<>();
            try {
                // when
                for (int i = 0; i < 3; i++) {
                    long started = System.nanoTime();
                    MvcTestResult checkout = http.post().uri("/api/orders").header("Idempotency-Key", newKey())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"buyerId\":1002,\"product\":\"mouse\"}").exchange();
                    Duration took = Duration.ofNanos(System.nanoTime() - started);

                    // then
                    assertThat(checkout).hasStatus(HttpStatus.CREATED);
                    assertThat(took).as("TB가 끊긴 동안 결제에 걸린 시간").isLessThan(Duration.ofSeconds(1));
                    accepted.add(((Number) JsonPath.read(body(checkout), "$.id")).longValue());
                }
                assertThat(http.get().uri("/api/demo/ledger")).bodyJson().extractingPath("$.connected").isEqualTo(false);
            } finally {
                assertThat(http.post().uri("/api/demo/ledger/restore")).hasStatus(HttpStatus.OK);
            }

            // then
            for (long orderId : accepted) {
                probe.awaitState(orderId, OrderState.AUTHORIZED);
            }
            assertThat(http.get().uri("/api/demo/ledger")).bodyJson().extractingPath("$.connected").isEqualTo(true);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("잘못된 확정을 넣으면")
    class BadPost {

        long orderId;
        String postId;
        long heldBefore;

        @BeforeEach
        void givenAnInjectedBadPost() throws Exception {
            probe.awaitQuiet();
            heldBefore = probe.heldBy(1003);
            MvcTestResult injected = http.post().uri("/api/demo/bad-post").exchange();
            assertThat(injected).hasStatus(HttpStatus.OK);
            orderId = ((Number) JsonPath.read(body(injected), "$.id")).longValue();
            postId = ShopOrder.commandId(orderId, "post");
            awaitHalted(postId);
        }

        @Test
        @DisplayName("그 주문의 확정만 '예약보다 앞 순번'으로 멈추고, 다른 주문은 결제 완료까지 간다")
        void onlyTheInjectedOrderHalts() throws Exception {
            // when
            ShopOrder other = shop.checkout(newKey(), 1004, "mouse").order();
            probe.awaitState(other.id(), OrderState.AUTHORIZED);
            shop.ship(other.id());

            // then
            probe.awaitState(other.id(), OrderState.PAID);
            assertThat(haltedOf(postId)).as("멈춘 이유").isEqualTo("RESERVATION_NOT_EARLIER");
            assertThat(orders.find(orderId).orElseThrow().state()).as("주입한 주문").isEqualTo(OrderState.ACCEPTED);
            abandon(postId);
        }

        @Test
        @DisplayName("운영자가 포기하면 해제가 뒤따라 주문은 취소됨이 되고, 구매자 묶인 돈이 원래대로 돌아온다")
        void abandoningTheHaltedPostEndsCancelled() throws Exception {
            // when
            MvcTestResult abandoned = abandon(postId);

            // then
            assertThat(abandoned).bodyJson().extractingPath("$.outcome").isEqualTo("ABANDONED");
            probe.awaitState(orderId, OrderState.CANCELLED);
            assertThat(orders.commandsOf(orderId)).as("주문의 명령(순번 순)").extracting(OrderCommand::kind)
                    .containsExactly(CommandKind.POST, CommandKind.RESERVE, CommandKind.VOID);
            assertThat(probe.heldBy(1003)).as("구매자에게 묶인 돈").isEqualTo(heldBefore);
        }

        @Test
        @DisplayName("멈추지 않은 명령을 포기하면 409와 이유 NOT_HALTED로 거부된다")
        void abandoningARunningCommandIsRefused() throws Exception {
            // given
            ShopOrder authorized = probe.awaitState(shop.checkout(newKey(), 1004, "mouse").order().id(),
                    OrderState.AUTHORIZED);

            // when
            MvcTestResult refused = abandon(ShopOrder.commandId(authorized.id(), "reserve"));

            // then
            assertThat(refused).hasStatus(HttpStatus.CONFLICT);
            assertThat(refused).bodyJson().extractingPath("$.reason").isEqualTo("NOT_HALTED");
            abandon(postId);
        }

        @Test
        @DisplayName("없는 명령을 포기하면 404다")
        void abandoningAnUnknownCommandIsNotFound() {
            assertThat(abandon("never-accepted")).hasStatus(HttpStatus.NOT_FOUND);
            abandon(postId);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("대사를 돌리면")
    class Reconciling {

        @Test
        @DisplayName("화면 아래 대사는 늦게 끝난 명령도 빠뜨리지 않는다 — 멈췄다 포기된 확정과 그 뒤 예약까지 센다")
        void theRunningReconciliationCountsCommandsThatFinishLate() throws Exception {
            // given: 잘못된 확정과 그 뒤 예약이 멈춰 있는 동안 다른 주문이 끝난다(행 순서로는 그 뒤다)
            long injected = ((Number) JsonPath.read(body(http.post().uri("/api/demo/bad-post").exchange()), "$.id"))
                    .longValue();
            String postId = ShopOrder.commandId(injected, "post");
            awaitHalted(postId);
            ShopOrder other = shop.checkout(newKey(), 1004, "mouse").order();
            probe.awaitState(other.id(), OrderState.AUTHORIZED);
            Thread.sleep(2_000);

            // when: 포기해 멈췄던 명령들이 늦게 끝난다
            abandon(postId);
            probe.awaitState(injected, OrderState.CANCELLED);
            probe.awaitQuiet();

            // then
            long finished = JdbcClient.create(dataSource).sql("SELECT count(*) FROM ledger_command "
                    + "WHERE state IN ('APPLIED','REJECTED','SKIPPED')").query(Long.class).single();
            long deadline = System.nanoTime() + ShopProbe.SETTLE.toNanos();
            long checked;
            do {
                Thread.sleep(200);
                checked = ((Number) JsonPath.read(body(http.get().uri("/api/consistency").exchange()), "$.checked"))
                        .longValue();
            } while (checked != finished && System.nanoTime() < deadline);
            assertThat(checked).as("대사로 확인한 명령 수").isEqualTo(finished);
        }

        @Test
        @DisplayName("장면을 다 돈 뒤에도 PG와 TB의 불일치가 0건이다")
        void reconcileFindsNoMismatch() throws Exception {
            // given
            probe.awaitQuiet();

            // when
            MvcTestResult report = http.post().uri("/api/operator/reconcile").exchange();

            // then
            assertThat(report).hasStatus(HttpStatus.OK);
            String json = body(report);
            long checked = ((Number) JsonPath.read(json, "$.checked")).longValue();
            assertThat(checked).as("확인한 명령 수").isPositive();
            assertThat(((Number) JsonPath.read(json, "$.matched")).longValue()).as("일치한 명령 수").isEqualTo(checked);
            assertThat((List<?>) JsonPath.read(json, "$.mismatches")).as("불일치").isEmpty();
        }
    }

    @Nested
    @Order(4)
    @DisplayName("50건을 몰아넣으면")
    class Burst {

        @Test
        @DisplayName("50건 모두 결제 승인됨이 되고, 각 주문의 예약 명령은 하나씩이다")
        void aBurstOfFiftyIsAllAuthorized() throws Exception {
            // when
            MvcTestResult burst = http.post().uri("/api/demo/burst?count=50").exchange();

            // then
            assertThat(burst).hasStatus(HttpStatus.OK);
            List<Number> ids = JsonPath.read(body(burst), "$.orderIds");
            assertThat(ids).as("만든 주문").hasSize(50).doesNotHaveDuplicates();
            for (Number id : ids) {
                probe.awaitState(id.longValue(), OrderState.AUTHORIZED);
                assertThat(orders.commandsOf(id.longValue())).as("주문 %s의 명령", id).extracting(OrderCommand::kind)
                        .containsExactly(CommandKind.RESERVE);
            }
        }

        @Test
        @DisplayName("1건 미만이나 200건 넘게 몰아넣으라고 하면 400이다")
        void anOutOfRangeBurstIsRefused() {
            assertThat(http.post().uri("/api/demo/burst?count=0")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(http.post().uri("/api/demo/burst?count=201")).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private MvcTestResult abandon(String commandId) {
        return http.post().uri("/api/operator/halted/{commandId}/abandon", commandId).exchange();
    }

    /** 멈춘 명령 목록에서 이 명령의 이유. 없으면 null. */
    private String haltedOf(String commandId) throws Exception {
        List<String> reasons = JsonPath.read(body(http.get().uri("/api/operator/halted?limit=1000").exchange()),
                "$[?(@.commandId == '" + commandId + "')].reason");
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    private void awaitHalted(String commandId) throws Exception {
        long deadline = System.nanoTime() + ShopProbe.SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            if (haltedOf(commandId) != null) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(commandId + "가 " + ShopProbe.SETTLE + " 안에 멈추지 않았다");
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String newKey() {
        return "demo-test-" + UUID.randomUUID();
    }
}
