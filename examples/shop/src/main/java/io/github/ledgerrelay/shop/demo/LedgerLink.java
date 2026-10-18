package io.github.ledgerrelay.shop.demo;

import io.github.ledgerrelay.shop.ShopProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 앱과 TB 사이의 Toxiproxy를 끊고 잇는다. 앱은 TB에 이 프록시로만 붙는다. Docker 소켓을 앱에 주지 않고 장애를
 * 낸다. 끊으면 이미 열린 연결도 닫힌다.
 */
@Component
@Profile("demo")
class LedgerLink {

    private static final Pattern ENABLED = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI proxy;

    LedgerLink(ShopProperties properties) {
        this.proxy = URI.create(properties.demo().toxiproxyUrl() + "/proxies/" + properties.demo().toxiproxyProxy());
    }

    boolean connected() {
        var matcher = ENABLED.matcher(send(HttpRequest.newBuilder(proxy).GET()));
        if (!matcher.find()) {
            throw new IllegalStateException("Toxiproxy 응답에 enabled가 없습니다");
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    void connect(boolean enabled) {
        send(HttpRequest.newBuilder(proxy).POST(HttpRequest.BodyPublishers.ofString("{\"enabled\":" + enabled + "}"))
                .header("Content-Type", "application/json"));
    }

    private String send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Toxiproxy가 " + response.statusCode() + "로 답했습니다: " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Toxiproxy에 닿지 못했습니다: " + proxy, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Toxiproxy 요청이 중단됐습니다", e);
        }
    }
}
