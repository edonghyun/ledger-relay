package io.github.ledgerrelay.shop.demo;

import static io.github.ledgerrelay.shop.ShopAssertions.assertAnswered;
import static io.github.ledgerrelay.shop.ShopAssertions.assertCommands;
import static io.github.ledgerrelay.shop.ShopAssertions.assertHoldReleased;
import static io.github.ledgerrelay.shop.ShopAssertions.assertStillIn;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.tigerbeetle.Client;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.shop.ShopProbe;
import io.github.ledgerrelay.shop.ShopSpringTest;
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
@DisplayName("Demo controls — failures caused on purpose and resolved by the operator")
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
    @DisplayName("When the TB connection is cut")
    class LedgerOutage {

        @Test
        @DisplayName("Payments are accepted within 1 second without waiting for TB and become authorized once the connection is restored")
        void checkoutDoesNotWaitForTheLedger() throws Exception {
            // given
            setLink("cut");
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
                    assertAcceptedWithin(checkout, took, Duration.ofSeconds(1));
                    accepted.add(idOf(checkout));
                }
                assertLinkConnected(false);
            } finally {
                setLink("restore");
            }

            // then
            for (long orderId : accepted) {
                probe.awaitState(orderId, OrderState.AUTHORIZED);
            }
            assertLinkConnected(true);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a bad post is injected")
    class BadPost {

        long orderId;
        String postId;
        long heldBefore;

        @BeforeEach
        void givenAnInjectedBadPost() throws Exception {
            probe.awaitQuiet();
            heldBefore = probe.heldBy(1003);
            orderId = injectBadPost();
            postId = ShopOrder.commandId(orderId, "post");
            awaitHalted(postId);
        }

        @Test
        @DisplayName("Only that order's post halts as 'earlier than the reservation' and other orders reach paid")
        void onlyTheInjectedOrderHalts() throws Exception {
            // when
            ShopOrder other = shop.checkout(newKey(), 1004, "mouse").order();
            probe.awaitState(other.id(), OrderState.AUTHORIZED);
            shop.ship(other.id());

            // then
            probe.awaitState(other.id(), OrderState.PAID);
            assertHaltedFor(postId, "RESERVATION_NOT_EARLIER");
            assertStillIn(orders, orderId, OrderState.ACCEPTED);
            abandon(postId);
        }

        @Test
        @DisplayName("Abandoning it makes a void follow, the order becomes cancelled and the buyer's held money returns to what it was")
        void abandoningTheHaltedPostEndsCancelled() throws Exception {
            // when
            MvcTestResult abandoned = abandon(postId);

            // then
            assertAbandoned(abandoned);
            probe.awaitState(orderId, OrderState.CANCELLED);
            assertCommands(orders, orderId, CommandKind.POST, CommandKind.RESERVE, CommandKind.VOID);
            assertHoldReleased(probe, 1003, heldBefore);
        }

        @Test
        @DisplayName("Abandoning a command that is not halted is refused with 409 and reason NOT_HALTED")
        void abandoningARunningCommandIsRefused() throws Exception {
            // given
            ShopOrder authorized = probe.awaitState(shop.checkout(newKey(), 1004, "mouse").order().id(),
                    OrderState.AUTHORIZED);

            // when
            MvcTestResult refused = abandon(ShopOrder.commandId(authorized.id(), "reserve"));

            // then
            assertAbandonRefusedFor(refused, "NOT_HALTED");
            abandon(postId);
        }

        @Test
        @DisplayName("Abandoning an unknown command answers 404")
        void abandoningAnUnknownCommandIsNotFound() {
            // when
            MvcTestResult unknown = abandon("never-accepted");

            // then
            assertAnswered(unknown, HttpStatus.NOT_FOUND);
            abandon(postId);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When reconciliation runs")
    class Reconciling {

        @Test
        @DisplayName("The running reconciliation in the UI misses no late-finishing command — it counts the halted-then-abandoned post and the reservation after it")
        void theRunningReconciliationCountsCommandsThatFinishLate() throws Exception {
            // given: another order finishes while the bad post and the reservation after it are halted (it comes later in row order)
            long injected = injectBadPost();
            String postId = ShopOrder.commandId(injected, "post");
            awaitHalted(postId);
            ShopOrder other = shop.checkout(newKey(), 1004, "mouse").order();
            probe.awaitState(other.id(), OrderState.AUTHORIZED);
            Thread.sleep(2_000);

            // when: abandoning makes the halted commands finish late
            abandon(postId);
            probe.awaitState(injected, OrderState.CANCELLED);
            probe.awaitQuiet();

            // then
            assertRunningReconciliationChecks(finishedCommands());
        }

        @Test
        @DisplayName("After all scenarios, PG and TB have zero mismatches")
        void reconcileFindsNoMismatch() throws Exception {
            // given
            probe.awaitQuiet();

            // when
            MvcTestResult report = http.post().uri("/api/operator/reconcile").exchange();

            // then
            assertEveryCheckedCommandMatched(report);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When a burst of 50 orders is placed")
    class Burst {

        @Test
        @DisplayName("All 50 become authorized and each order has exactly one reservation command")
        void aBurstOfFiftyIsAllAuthorized() throws Exception {
            // when
            MvcTestResult burst = http.post().uri("/api/demo/burst?count=50").exchange();

            // then
            List<Long> ids = assertDistinctOrdersCreated(burst, 50);
            for (long id : ids) {
                probe.awaitState(id, OrderState.AUTHORIZED);
                assertCommands(orders, id, CommandKind.RESERVE);
            }
        }

        @Test
        @DisplayName("A burst of fewer than 1 or more than 200 orders answers 400")
        void anOutOfRangeBurstIsRefused() {
            // when
            MvcTestResult none = http.post().uri("/api/demo/burst?count=0").exchange();
            MvcTestResult tooMany = http.post().uri("/api/demo/burst?count=201").exchange();

            // then
            assertAnswered(none, HttpStatus.BAD_REQUEST);
            assertAnswered(tooMany, HttpStatus.BAD_REQUEST);
        }
    }

    // ── Effects checked only in this file ────────────────────────────────

    private static void assertAcceptedWithin(MvcTestResult checkout, Duration took, Duration limit) {
        assertThat(checkout).hasStatus(HttpStatus.CREATED);
        assertThat(took).as("payment time while TB is cut").isLessThan(limit);
    }

    private void assertLinkConnected(boolean connected) {
        assertThat(http.get().uri("/api/demo/ledger")).bodyJson().extractingPath("$.connected").isEqualTo(connected);
    }

    private void assertHaltedFor(String commandId, String reason) throws Exception {
        assertThat(haltedOf(commandId)).as("halt reason of %s", commandId).isEqualTo(reason);
    }

    private static void assertAbandoned(MvcTestResult result) {
        assertThat(result).bodyJson().extractingPath("$.outcome").isEqualTo("ABANDONED");
    }

    private static void assertAbandonRefusedFor(MvcTestResult result, String reason) {
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.reason").isEqualTo(reason);
    }

    /** The UI's running reconciliation reaches exactly this many checked commands within the settle time. */
    private void assertRunningReconciliationChecks(long expected) throws Exception {
        long deadline = System.nanoTime() + ShopProbe.SETTLE.toNanos();
        long checked;
        do {
            Thread.sleep(200);
            checked = ((Number) JsonPath.read(body(http.get().uri("/api/consistency").exchange()), "$.checked"))
                    .longValue();
        } while (checked != expected && System.nanoTime() < deadline);
        assertThat(checked).as("commands checked by reconciliation").isEqualTo(expected);
    }

    private static void assertEveryCheckedCommandMatched(MvcTestResult report) throws Exception {
        assertThat(report).hasStatus(HttpStatus.OK);
        String json = body(report);
        long checked = ((Number) JsonPath.read(json, "$.checked")).longValue();
        assertThat(checked).as("checked commands").isPositive();
        assertThat(((Number) JsonPath.read(json, "$.matched")).longValue()).as("matched commands").isEqualTo(checked);
        assertThat((List<?>) JsonPath.read(json, "$.mismatches")).as("mismatches").isEmpty();
    }

    /** The burst answered with {@code count} different order ids. Returns them. */
    private static List<Long> assertDistinctOrdersCreated(MvcTestResult burst, int count) throws Exception {
        assertThat(burst).hasStatus(HttpStatus.OK);
        List<Number> ids = JsonPath.read(body(burst), "$.orderIds");
        assertThat(ids).as("created orders").hasSize(count).doesNotHaveDuplicates();
        return ids.stream().map(Number::longValue).toList();
    }

    // ── Helpers used only in this file ───────────────────────────────────

    /** given setup: cuts or restores the TB connection through the demo API. Fails here if the API refuses. */
    private void setLink(String action) {
        MvcTestResult result = http.post().uri("/api/demo/ledger/" + action).exchange();
        if (result.getResponse().getStatus() != HttpStatus.OK.value()) {
            throw new IllegalStateException("given setup failed: ledger " + action + " answered " + result.getResponse().getStatus());
        }
    }

    /** given setup: injects a post that skips the acceptance checks. Returns its order id. */
    private long injectBadPost() throws Exception {
        MvcTestResult injected = http.post().uri("/api/demo/bad-post").exchange();
        if (injected.getResponse().getStatus() != HttpStatus.OK.value()) {
            throw new IllegalStateException("given setup failed: bad post injection answered " + injected.getResponse().getStatus());
        }
        return idOf(injected);
    }

    private long finishedCommands() {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM ledger_command "
                + "WHERE state IN ('APPLIED','REJECTED','SKIPPED')").query(Long.class).single();
    }

    private MvcTestResult abandon(String commandId) {
        return http.post().uri("/api/operator/halted/{commandId}/abandon", commandId).exchange();
    }

    /** The halt reason of this command from the halted list, or null if it is not there. */
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
        throw new AssertionError(commandId + " did not halt within " + ShopProbe.SETTLE);
    }

    private static long idOf(MvcTestResult result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String newKey() {
        return "demo-test-" + UUID.randomUUID();
    }
}
