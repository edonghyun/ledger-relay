package io.github.ledgerrelay.shop.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.tigerbeetle.Client;
import io.github.ledgerrelay.shop.ShopProbe;
import io.github.ledgerrelay.shop.ShopProperties;
import io.github.ledgerrelay.shop.ShopSpringTest;
import io.github.ledgerrelay.shop.order.OrderService;
import io.github.ledgerrelay.shop.order.OrderState;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@DisplayName("상태 API — 화면의 숫자는 모두 PG와 TB에서 온다 (03b §7.1, 반목표)")
class StatusApiTest extends ShopSpringTest {

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
    @Autowired
    ShopProperties properties;

    ShopProbe probe;

    @BeforeEach
    void probe() {
        probe = new ShopProbe(orders, client, dataSource);
    }

    @Test
    @DisplayName("결제 하나를 출고까지 보내면 접수·완료가 각각 2씩 늘고 대기는 그대로다")
    void tilesCountCommands() throws Exception {
        // given
        probe.awaitQuiet();
        String before = get("/api/status");

        // when
        paidOrder();
        probe.awaitQuiet();

        // then
        String after = get("/api/status");
        assertThat(number(after, "$.tiles.accepted") - number(before, "$.tiles.accepted")).as("접수 증가").isEqualTo(2);
        assertThat(number(after, "$.tiles.completed") - number(before, "$.tiles.completed")).as("완료 증가").isEqualTo(2);
        assertThat(number(after, "$.tiles.backlog")).as("대기").isEqualTo(number(before, "$.tiles.backlog"));
        assertThat(number(after, "$.tiles.p99Millis")).as("p99 접수→완료(ms)").isPositive();
    }

    @Test
    @DisplayName("결제 완료 뒤 정합성은 ok이고, 가맹점 확정 잔액이 결제 완료 주문 합과 같다")
    void consistencyIsOkAfterAPaidOrder() throws Exception {
        // given
        paidOrder();
        probe.awaitQuiet();

        // when
        String consistency = await("/api/consistency", json -> "ok".equals(JsonPath.read(json, "$.verdict"))
                && number(json, "$.merchantPostedSatang") == number(json, "$.paidOrdersSatang")
                && number(json, "$.paidOrdersSatang") > 0);

        // then
        assertThat(number(consistency, "$.mismatches")).as("대사 불일치").isZero();
        assertThat(number(consistency, "$.checked")).as("대사로 확인한 명령").isEqualTo(number(consistency, "$.matched"));
        assertThat(number(consistency, "$.doubleTransfers")).as("중복 이체").isZero();
    }

    @Test
    @DisplayName("타임라인은 예약과 확정 단계, TB 전송 횟수, 실제 잔액을 보여 준다")
    void timelineShowsStepsAndBalances() throws Exception {
        // given
        ShopOrder order = paidOrder();

        // when
        String timeline = await("/api/orders/" + order.id() + "/timeline",
                json -> JsonPath.read(json, "$.merchant") != null);

        // then
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].kind")).as("단계").containsExactly("RESERVE", "POST");
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].state")).as("단계 결과")
                .containsExactly("APPLIED", "APPLIED");
        assertThat((List<Number>) JsonPath.read(timeline, "$.steps[*].submitCount")).as("TB 전송 횟수")
                .allSatisfy(count -> assertThat(count.longValue()).isGreaterThanOrEqualTo(1));
        assertThat((String) JsonPath.read(timeline, "$.steps[0].transferId")).as("예약 이체 ID(16진)").startsWith("0x");
        assertThat(number(timeline, "$.order.amountSatang")).as("주문 금액").isEqualTo(89_000);
        assertThat(number(timeline, "$.merchant.postedSatang")).as("가맹점 확정 잔액").isGreaterThanOrEqualTo(89_000);
        assertThat(number(timeline, "$.buyer.paidSatang")).as("구매자가 낸 돈").isGreaterThanOrEqualTo(89_000);
    }

    @Test
    @DisplayName("이 인스턴스가 몇 초 안에 Workers 목록에 보이고, TB 연결은 UP으로 보인다")
    void theInstanceShowsUpWithinSeconds() throws Exception {
        // TB 확인은 기다리지 않고 뒤에서 돈다(F36). 첫 응답은 아직 답을 받기 전일 수 있어 폴링으로 기다린다
        String status = await("/api/status", json -> !((List<?>) JsonPath.read(json,
                "$.instances[?(@.name == '" + properties.instanceName() + "')]")).isEmpty()
                && Boolean.TRUE.equals(JsonPath.read(json, "$.ledgerUp")));

        assertThat(number(status, "$.instances.length()")).as("살아 있는 인스턴스 수").isPositive();
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private ShopOrder paidOrder() throws InterruptedException {
        ShopOrder order = shop.checkout("status-" + UUID.randomUUID(), 1002, "mouse").order();
        probe.awaitState(order.id(), OrderState.AUTHORIZED);
        shop.ship(order.id());
        return probe.awaitState(order.id(), OrderState.PAID);
    }

    private String get(String path) throws Exception {
        return http.get().uri(path).exchange().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String await(String path, Predicate<String> ready) throws Exception {
        long deadline = System.nanoTime() + ShopProbe.SETTLE.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            last = get(path);
            if (ready.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(path + "가 " + ShopProbe.SETTLE + " 안에 기대한 값이 되지 않았다. 마지막 응답: " + last);
    }

    static long number(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
