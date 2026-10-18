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

@DisplayName("Status API — every number in the UI comes from PG and TB (anti-goal)")
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
    @DisplayName("Taking one payment through shipping raises accepted and completed by 2 each and leaves the backlog unchanged")
    void tilesCountCommands() throws Exception {
        // given
        probe.awaitQuiet();
        String before = get("/api/status");

        // when
        paidOrder();
        probe.awaitQuiet();

        // then
        String after = get("/api/status");
        assertThat(number(after, "$.tiles.accepted") - number(before, "$.tiles.accepted")).as("accepted increase").isEqualTo(2);
        assertThat(number(after, "$.tiles.completed") - number(before, "$.tiles.completed")).as("completed increase").isEqualTo(2);
        assertThat(number(after, "$.tiles.backlog")).as("backlog").isEqualTo(number(before, "$.tiles.backlog"));
        assertThat(number(after, "$.tiles.p99Millis")).as("p99 accept→done (ms)").isPositive();
    }

    @Test
    @DisplayName("After a paid order the verdict is ok and the merchant posted balance equals the sum of paid orders")
    void consistencyIsOkAfterAPaidOrder() throws Exception {
        // given
        paidOrder();
        probe.awaitQuiet();

        // when
        String consistency = await("/api/consistency", json -> "ok".equals(JsonPath.read(json, "$.verdict"))
                && number(json, "$.merchantPostedSatang") == number(json, "$.paidOrdersSatang")
                && number(json, "$.paidOrdersSatang") > 0);

        // then
        assertThat(number(consistency, "$.mismatches")).as("reconciliation mismatches").isZero();
        assertThat(number(consistency, "$.checked")).as("commands checked by reconciliation").isEqualTo(number(consistency, "$.matched"));
        assertThat(number(consistency, "$.doubleTransfers")).as("double transfers").isZero();
    }

    @Test
    @DisplayName("The timeline shows the reserve and post steps, the TB submit counts and the actual balances")
    void timelineShowsStepsAndBalances() throws Exception {
        // given
        ShopOrder order = paidOrder();

        // when: balances are collected every second, so wait until the paid order shows up
        String timeline = await("/api/orders/" + order.id() + "/timeline",
                json -> JsonPath.read(json, "$.buyer") != null && number(json, "$.buyer.paidSatang") >= 89_000);

        // then
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].kind")).as("steps").containsExactly("RESERVE", "POST");
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].state")).as("step results")
                .containsExactly("APPLIED", "APPLIED");
        assertThat((List<Number>) JsonPath.read(timeline, "$.steps[*].submitCount")).as("TB submit counts")
                .allSatisfy(count -> assertThat(count.longValue()).isGreaterThanOrEqualTo(1));
        assertThat((String) JsonPath.read(timeline, "$.steps[0].transferId")).as("reservation transfer id (hex)").startsWith("0x");
        assertThat(number(timeline, "$.order.amountSatang")).as("order amount").isEqualTo(89_000);
        assertThat(number(timeline, "$.merchant.postedSatang")).as("merchant posted balance").isGreaterThanOrEqualTo(89_000);
        assertThat(number(timeline, "$.buyer.paidSatang")).as("money paid by the buyer").isGreaterThanOrEqualTo(89_000);
    }

    @Test
    @DisplayName("This instance appears in the Workers list within seconds and the TB connection shows as UP")
    void theInstanceShowsUpWithinSeconds() throws Exception {
        // The TB check runs in the background without waiting (F36). The first response may come before any answer, so poll
        String status = await("/api/status", json -> !((List<?>) JsonPath.read(json,
                "$.instances[?(@.name == '" + properties.instanceName() + "')]")).isEmpty()
                && Boolean.TRUE.equals(JsonPath.read(json, "$.ledgerUp")));

        assertThat(number(status, "$.instances.length()")).as("live instances").isPositive();
    }

    // ── Helpers used only in this file ───────────────────────────────────

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
        throw new AssertionError(path + " did not reach the expected value within " + ShopProbe.SETTLE + ". Last response: " + last);
    }

    static long number(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
