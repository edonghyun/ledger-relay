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
        assertTileRose(before, after, "accepted", 2);
        assertTileRose(before, after, "completed", 2);
        assertTileRose(before, after, "backlog", 0);
        assertLatencyMeasured(after);
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
        assertEveryCheckedCommandMatched(consistency);
        assertNoOrderChargedTwice(consistency);
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
        assertStepsApplied(timeline, "RESERVE", "POST");
        assertEveryStepSentToTheLedger(timeline);
        assertBalancesShownFor(timeline, 89_000);
    }

    @Test
    @DisplayName("This instance appears in the Workers list within seconds and the TB connection shows as UP")
    void theInstanceShowsUpWithinSeconds() throws Exception {
        // when: the TB check runs in the background without waiting (F36). The first response may come before any
        // answer, so poll
        String status = await("/api/status", json -> !((List<?>) JsonPath.read(json,
                "$.instances[?(@.name == '" + properties.instanceName() + "')]")).isEmpty()
                && Boolean.TRUE.equals(JsonPath.read(json, "$.ledgerUp")));

        // then
        assertInstancesListed(status);
    }

    // ── Effects checked only in this file ────────────────────────────────

    private static void assertTileRose(String before, String after, String tile, long by) {
        String path = "$.tiles." + tile;
        assertThat(number(after, path) - number(before, path)).as("rise of the %s tile", tile).isEqualTo(by);
    }

    private static void assertLatencyMeasured(String status) {
        assertThat(number(status, "$.tiles.p99Millis")).as("p99 accept→done (ms)").isPositive();
    }

    private static void assertEveryCheckedCommandMatched(String consistency) {
        assertThat(number(consistency, "$.mismatches")).as("reconciliation mismatches").isZero();
        assertThat(number(consistency, "$.checked")).as("commands checked by reconciliation")
                .isEqualTo(number(consistency, "$.matched"));
    }

    private static void assertNoOrderChargedTwice(String consistency) {
        assertThat(number(consistency, "$.doubleTransfers")).as("orders charged twice").isZero();
    }

    @SuppressWarnings("unchecked")
    private static void assertStepsApplied(String timeline, String... kinds) {
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].kind")).as("steps").containsExactly(kinds);
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].state")).as("step results")
                .hasSize(kinds.length).containsOnly("APPLIED");
    }

    @SuppressWarnings("unchecked")
    private static void assertEveryStepSentToTheLedger(String timeline) {
        assertThat((List<Number>) JsonPath.read(timeline, "$.steps[*].submitCount")).as("TB submit counts")
                .allSatisfy(count -> assertThat(count.longValue()).isGreaterThanOrEqualTo(1));
        assertThat((List<String>) JsonPath.read(timeline, "$.steps[*].transferId")).as("transfer ids (hex)")
                .allSatisfy(id -> assertThat(id).startsWith("0x"));
    }

    /** The order amount and the balances it moved are shown. Balances are at least the amount: other orders share them. */
    private static void assertBalancesShownFor(String timeline, long amount) {
        assertThat(number(timeline, "$.order.amountSatang")).as("order amount").isEqualTo(amount);
        assertThat(number(timeline, "$.merchant.postedSatang")).as("merchant posted balance").isGreaterThanOrEqualTo(amount);
        assertThat(number(timeline, "$.buyer.paidSatang")).as("money paid by the buyer").isGreaterThanOrEqualTo(amount);
    }

    private static void assertInstancesListed(String status) {
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
