package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

/**
 * Starts the {@code compose.yaml} at the repository root as is and runs scenarios 1-5 of the README over HTTP. It also
 * verifies the app image build, the two instances and the TB startup. The test exists so that {@code docker compose up}
 * does not fail for someone who has just cloned the repository.
 */
@DisplayName("Sample shop started with a single docker compose up — scenarios 1-5")
class ShopComposeTest {

    static final Duration SETTLE = Duration.ofSeconds(30);
    static final long MOUSE = 89_000;
    static final long EARBUDS = 149_000;

    static ComposeContainer compose;
    static String base;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll
    static void composeUp() throws Exception {
        int port = freePort();
        compose = new ComposeContainer("relayshop-e2e-" + UUID.randomUUID().toString().substring(0, 8),
                new File("../../compose.yaml"))
                .withBuild(true)
                .withEnv("SHOP_PORT", String.valueOf(port))
                .withRemoveVolumes(true);
        compose.start();
        base = "http://127.0.0.1:" + port;
        await(Duration.ofMinutes(5), "/actuator/health", json -> "UP".equals(JsonPath.read(json, "$.status")));
    }

    @AfterAll
    static void composeDown() {
        if (compose != null) {
            compose.stop();
        }
    }

    @AfterEach
    void theBooksStillBalance() throws Exception {
        await(SETTLE, "/api/consistency", json -> "ok".equals(JsonPath.read(json, "$.verdict"))
                && !(Boolean) JsonPath.read(json, "$.stale"));
    }

    @Test
    @Order(1)
    @DisplayName("Scenario 1 — paying makes the order authorized, shipping makes it paid and the merchant receives the amount")
    void scene1HappyPath() throws Exception {
        // given
        long merchantBefore = number(get("/api/consistency"), "$.merchantPostedSatang");

        // when
        long order = checkout(UUID.randomUUID().toString(), 1001, "earbuds").id();
        awaitOrder(order, "AUTHORIZED");
        post("/api/orders/" + order + "/ship");

        // then
        awaitOrder(order, "PAID");
        String consistency = await(SETTLE, "/api/consistency",
                json -> number(json, "$.merchantPostedSatang") == merchantBefore + EARBUDS);
        assertThat(number(consistency, "$.paidOrdersSatang")).as("sum of paid orders").isEqualTo(merchantBefore + EARBUDS);
    }

    @Test
    @Order(2)
    @DisplayName("Scenario 2 — with TB cut, payments are still accepted and the backlog grows; after restoring, everything is processed")
    void scene2LedgerOutage() throws Exception {
        // given
        post("/api/demo/ledger/cut");
        long[] orders = new long[3];
        try {
            // when
            for (int i = 0; i < orders.length; i++) {
                long started = System.nanoTime();
                Checkout checkout = checkout(UUID.randomUUID().toString(), 1002, "mouse");
                assertThat(checkout.status()).as("payment response while TB is cut").isEqualTo(201);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("payment time")
                        .isLessThan(Duration.ofSeconds(1));
                orders[i] = checkout.id();
            }

            // then: the backlog grows and the TB dot in the UI turns red
            assertThat(number(get("/api/status"), "$.tiles.backlog")).as("backlog").isGreaterThanOrEqualTo(3);
            await(SETTLE, "/api/status", json -> Boolean.FALSE.equals(JsonPath.read(json, "$.ledgerUp")));
        } finally {
            post("/api/demo/ledger/restore");
        }

        // then: after restoring, every order is authorized
        for (long order : orders) {
            awaitOrder(order, "AUTHORIZED");
        }
        await(SETTLE, "/api/status", json -> Boolean.TRUE.equals(JsonPath.read(json, "$.ledgerUp")));
    }

    @Test
    @Order(3)
    @DisplayName("Scenario 3 — a bad post halts only its order; abandoning it ends cancelled with the original balance")
    void scene3HaltAndAbandon() throws Exception {
        // given
        long heldBefore = heldBy(1003);
        long injected = number(post("/api/demo/bad-post"), "$.id");
        String postCommand = "order-" + injected + "-post";
        await(SETTLE, "/api/operator/halted",
                json -> !((List<?>) JsonPath.read(json, "$[?(@.commandId == '" + postCommand + "')]")).isEmpty());

        // when: other orders keep flowing
        long other = checkout(UUID.randomUUID().toString(), 1004, "mouse").id();
        awaitOrder(other, "AUTHORIZED");
        post("/api/orders/" + other + "/ship");
        awaitOrder(other, "PAID");

        // when: the operator abandons it
        String abandoned = post("/api/operator/halted/" + postCommand + "/abandon");

        // then
        assertThat((String) JsonPath.read(abandoned, "$.outcome")).as("abandon outcome").isEqualTo("ABANDONED");
        awaitOrder(injected, "CANCELLED");
        await(SETTLE, "/api/orders/" + injected + "/timeline",
                json -> JsonPath.read(json, "$.buyer") != null && number(json, "$.buyer.heldSatang") == heldBefore);
    }

    @Test
    @Order(4)
    @DisplayName("Scenario 4 — paying twice at the same time with the same key gives one order and one reservation")
    void scene4DoubleClick() throws Exception {
        // given
        String key = UUID.randomUUID().toString();

        // when
        CompletableFuture<Checkout> first = CompletableFuture.supplyAsync(() -> checkoutUnchecked(key));
        CompletableFuture<Checkout> second = CompletableFuture.supplyAsync(() -> checkoutUnchecked(key));
        Checkout a = first.get();
        Checkout b = second.get();

        // then
        assertThat(a.id()).as("order returned to both requests").isEqualTo(b.id());
        assertThat(List.of(a.status(), b.status())).as("response statuses").containsExactlyInAnyOrder(201, 200);
        awaitOrder(a.id(), "AUTHORIZED");
        assertThat((List<String>) JsonPath.read(get("/api/orders/" + a.id() + "/timeline"), "$.steps[*].kind"))
                .as("commands of the order").containsExactly("RESERVE");
    }

    @Test
    @Order(5)
    @DisplayName("Scenario 5 — cancelling before shipping releases the held money")
    void scene5Cancel() throws Exception {
        // given
        long heldBefore = heldBy(1001);
        long order = checkout(UUID.randomUUID().toString(), 1001, "keyboard").id();
        awaitOrder(order, "AUTHORIZED");

        // when
        post("/api/orders/" + order + "/cancel");

        // then
        awaitOrder(order, "CANCELLED");
        await(SETTLE, "/api/orders/" + order + "/timeline",
                json -> JsonPath.read(json, "$.buyer") != null && number(json, "$.buyer.heldSatang") == heldBefore);
    }

    @Test
    @Order(6)
    @DisplayName("A burst of 50 is fully authorized and both instances send to TB — the two workers split the work")
    void burstIsSharedByTwoInstances() throws Exception {
        // given
        String before = await(SETTLE, "/api/status", json -> ((List<?>) JsonPath.read(json, "$.instances")).size() == 2);
        Map<String, Long> sentBefore = sentByInstance(before);

        // when
        List<Number> ids = JsonPath.read(post("/api/demo/burst?count=50"), "$.orderIds");

        // then
        assertThat(ids).as("created orders").hasSize(50);
        for (Number id : ids) {
            awaitOrder(id.longValue(), "AUTHORIZED");
        }
        String after = await(SETTLE, "/api/status", json -> {
            Map<String, Long> sent = sentByInstance(json);
            return sent.size() == 2 && sent.entrySet().stream().allMatch(e -> e.getValue() > sentBefore.getOrDefault(e.getKey(), 0L));
        });
        assertThat(sentByInstance(after).keySet()).as("sending instances").containsExactlyInAnyOrder("shop-a", "shop-b");
    }

    // ── Helpers used only in this file ───────────────────────────────────

    record Checkout(int status, long id) {
    }

    private static Checkout checkout(String key, long buyer, String product) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"buyerId\":" + buyer + ",\"product\":\"" + product + "\"}"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("payment response " + response.body()).isIn(200, 201);
        return new Checkout(response.statusCode(), number(response.body(), "$.id"));
    }

    private static Checkout checkoutUnchecked(String key) {
        try {
            return checkout(key, 1002, "earbuds");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long heldBy(long buyer) throws Exception {
        long order = checkout(UUID.randomUUID().toString(), buyer, "mouse").id();
        awaitOrder(order, "AUTHORIZED");
        post("/api/orders/" + order + "/cancel");
        awaitOrder(order, "CANCELLED");
        // Balances are collected every second. Wait long enough for the cancel to show, then read until two reads in a row match
        long previous = -1;
        while (true) {
            Thread.sleep(1_500);
            String timeline = get("/api/orders/" + order + "/timeline");
            if (JsonPath.read(timeline, "$.buyer") != null) {
                long held = number(timeline, "$.buyer.heldSatang");
                if (held == previous) {
                    return held;
                }
                previous = held;
            }
        }
    }

    private static void awaitOrder(long order, String state) throws Exception {
        await(SETTLE, "/api/orders/" + order + "/timeline", json -> state.equals(JsonPath.read(json, "$.order.state")));
    }

    private static Map<String, Long> sentByInstance(String status) {
        Map<String, Long> sent = new HashMap<>();
        List<Map<String, Object>> instances = JsonPath.read(status, "$.instances");
        instances.forEach(i -> sent.put((String) i.get("name"), ((Number) i.get("submitted")).longValue()));
        return sent;
    }

    private static String get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    private static String post(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private static String send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(request.build().uri() + " → " + response.body()).isBetween(200, 299);
        return response.body();
    }

    private static String await(Duration limit, String path, Predicate<String> ready) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = get(path);
                if (ready.test(last)) {
                    return last;
                }
            } catch (IOException | AssertionError notYet) {
                last = notYet.toString();
            }
            Thread.sleep(250);
        }
        throw new AssertionError(path + " did not reach the expected value within " + limit + ". Last response: " + last);
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
