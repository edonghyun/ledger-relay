package io.github.ledgerrelay.shop.demo;

import io.github.ledgerrelay.shop.ShopProperties;
import jakarta.annotation.PostConstruct;
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
 * Cuts and restores the Toxiproxy between the app and TB. The app reaches TB only through this proxy, so the failure is
 * caused without giving the app the Docker socket. Cutting also closes connections that are already open.
 *
 * <p>A start restores the connection. A cut left over from before a restart would otherwise make the account setup
 * wait forever, and the button that restores the connection lives in this app (F45).
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

    /**
     * Runs before {@code ShopBootstrap}, which waits for TB after all beans are created. Tries for about 10 seconds:
     * a Toxiproxy that has only just started may not answer yet, and a fresh proxy is connected anyway.
     */
    @PostConstruct
    void restoreAtStartup() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                connect(true);
                return;
            } catch (IllegalStateException e) {
                if (attempt == 5) {
                    System.getLogger(LedgerLink.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not restore the TB connection at startup: " + e.getMessage());
                    return;
                }
                Thread.sleep(500);
            }
        }
    }

    boolean connected() {
        var matcher = ENABLED.matcher(send(HttpRequest.newBuilder(proxy).GET()));
        if (!matcher.find()) {
            throw new IllegalStateException("The Toxiproxy response has no enabled field");
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
                throw new IllegalStateException("Toxiproxy answered " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach Toxiproxy: " + proxy, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during the Toxiproxy request", e);
        }
    }
}
