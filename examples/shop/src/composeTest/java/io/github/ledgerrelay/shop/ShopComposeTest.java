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
 * 저장소 루트의 {@code compose.yaml}을 그대로 띄우고 README의 장면 1~5를 HTTP로 돈다(03b §7.6). 앱 이미지 빌드, 인스턴스 둘,
 * TB 기동까지 함께 검증한다. 면접관이 {@code docker compose up}을 처음 했을 때 실패하지 않게 하려는 테스트다.
 */
@DisplayName("docker compose up 한 번으로 뜬 샘플 쇼핑몰 — 장면 1~5 (03b §7.3, §7.6)")
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
    @DisplayName("장면 1 — 결제하면 결제 승인됨, 출고하면 결제 완료가 되고 가맹점이 금액을 받는다")
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
        assertThat(number(consistency, "$.paidOrdersSatang")).as("결제 완료 주문 합").isEqualTo(merchantBefore + EARBUDS);
    }

    @Test
    @Order(2)
    @DisplayName("장면 2 — TB를 끊어도 결제는 접수되고 대기가 쌓이며, 다시 이으면 모두 처리된다")
    void scene2LedgerOutage() throws Exception {
        // given
        post("/api/demo/ledger/cut");
        long[] orders = new long[3];
        try {
            // when
            for (int i = 0; i < orders.length; i++) {
                long started = System.nanoTime();
                Checkout checkout = checkout(UUID.randomUUID().toString(), 1002, "mouse");
                assertThat(checkout.status()).as("TB가 끊긴 동안 결제 응답").isEqualTo(201);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("결제에 걸린 시간")
                        .isLessThan(Duration.ofSeconds(1));
                orders[i] = checkout.id();
            }

            // then: 쌓이고, 화면의 TB 점이 빨강이 된다
            assertThat(number(get("/api/status"), "$.tiles.backlog")).as("대기").isGreaterThanOrEqualTo(3);
            await(SETTLE, "/api/status", json -> Boolean.FALSE.equals(JsonPath.read(json, "$.ledgerUp")));
        } finally {
            post("/api/demo/ledger/restore");
        }

        // then: 다시 이으면 모두 승인된다
        for (long order : orders) {
            awaitOrder(order, "AUTHORIZED");
        }
        await(SETTLE, "/api/status", json -> Boolean.TRUE.equals(JsonPath.read(json, "$.ledgerUp")));
    }

    @Test
    @Order(3)
    @DisplayName("장면 3 — 잘못된 확정은 그 주문만 멈추고, 포기하면 취소됨과 원래 잔액으로 끝난다")
    void scene3HaltAndAbandon() throws Exception {
        // given
        long heldBefore = heldBy(1003);
        long injected = number(post("/api/demo/bad-post"), "$.id");
        String postCommand = "order-" + injected + "-post";
        await(SETTLE, "/api/operator/halted",
                json -> !((List<?>) JsonPath.read(json, "$[?(@.commandId == '" + postCommand + "')]")).isEmpty());

        // when: 다른 주문은 흐른다
        long other = checkout(UUID.randomUUID().toString(), 1004, "mouse").id();
        awaitOrder(other, "AUTHORIZED");
        post("/api/orders/" + other + "/ship");
        awaitOrder(other, "PAID");

        // when: 운영자가 포기한다
        String abandoned = post("/api/operator/halted/" + postCommand + "/abandon");

        // then
        assertThat((String) JsonPath.read(abandoned, "$.outcome")).as("포기 결과").isEqualTo("ABANDONED");
        awaitOrder(injected, "CANCELLED");
        await(SETTLE, "/api/orders/" + injected + "/timeline",
                json -> JsonPath.read(json, "$.buyer") != null && number(json, "$.buyer.heldSatang") == heldBefore);
    }

    @Test
    @Order(4)
    @DisplayName("장면 4 — 같은 키로 동시에 두 번 결제하면 주문 하나, 예약 하나다")
    void scene4DoubleClick() throws Exception {
        // given
        String key = UUID.randomUUID().toString();

        // when
        CompletableFuture<Checkout> first = CompletableFuture.supplyAsync(() -> checkoutUnchecked(key));
        CompletableFuture<Checkout> second = CompletableFuture.supplyAsync(() -> checkoutUnchecked(key));
        Checkout a = first.get();
        Checkout b = second.get();

        // then
        assertThat(a.id()).as("두 요청이 받은 주문").isEqualTo(b.id());
        assertThat(List.of(a.status(), b.status())).as("응답 상태").containsExactlyInAnyOrder(201, 200);
        awaitOrder(a.id(), "AUTHORIZED");
        assertThat((List<String>) JsonPath.read(get("/api/orders/" + a.id() + "/timeline"), "$.steps[*].kind"))
                .as("주문의 명령").containsExactly("RESERVE");
    }

    @Test
    @Order(5)
    @DisplayName("장면 5 — 출고 전에 취소하면 묶였던 돈이 풀린다")
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
    @DisplayName("몰아넣기 50건이 모두 승인되고, 두 인스턴스가 모두 TB에 보낸다 — 워커 둘이 나눠 처리한다")
    void burstIsSharedByTwoInstances() throws Exception {
        // given
        String before = await(SETTLE, "/api/status", json -> ((List<?>) JsonPath.read(json, "$.instances")).size() == 2);
        Map<String, Long> sentBefore = sentByInstance(before);

        // when
        List<Number> ids = JsonPath.read(post("/api/demo/burst?count=50"), "$.orderIds");

        // then
        assertThat(ids).as("만든 주문").hasSize(50);
        for (Number id : ids) {
            awaitOrder(id.longValue(), "AUTHORIZED");
        }
        String after = await(SETTLE, "/api/status", json -> {
            Map<String, Long> sent = sentByInstance(json);
            return sent.size() == 2 && sent.entrySet().stream().allMatch(e -> e.getValue() > sentBefore.getOrDefault(e.getKey(), 0L));
        });
        assertThat(sentByInstance(after).keySet()).as("보낸 인스턴스").containsExactlyInAnyOrder("shop-a", "shop-b");
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    record Checkout(int status, long id) {
    }

    private static Checkout checkout(String key, long buyer, String product) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"buyerId\":" + buyer + ",\"product\":\"" + product + "\"}"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("결제 응답 " + response.body()).isIn(200, 201);
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
        // 잔액은 1초마다 모으는 값이다. 취소가 반영될 만큼 기다린 뒤, 연달아 두 번 같은 값을 읽을 때까지 본다
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
        throw new AssertionError(path + "가 " + limit + " 안에 기대한 값이 되지 않았다. 마지막 응답: " + last);
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
