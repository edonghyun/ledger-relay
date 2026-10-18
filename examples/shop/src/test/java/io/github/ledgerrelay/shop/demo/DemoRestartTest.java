package io.github.ledgerrelay.shop.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.shop.ShopApplication;
import io.github.ledgerrelay.shop.ShopTestEnvironment;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@DisplayName("Restarting the demo app while the TB connection is cut")
class DemoRestartTest {

    ShopTestEnvironment environment;

    @BeforeEach
    void isolatedEnvironment() {
        environment = ShopTestEnvironment.isolated();
    }

    @AfterEach
    void close() throws Exception {
        environment.ledger().restoreConnection();
        environment.close();
    }

    @Test
    @DisplayName("The app finishes starting and the connection is restored — otherwise the account setup waits forever "
            + "and the button that restores the connection never appears (F45)")
    void startupRestoresACutConnection() throws Exception {
        // given: a cut left over from an outage scene before the restart
        environment.ledger().cutConnection();

        // when
        CompletableFuture<ConfigurableApplicationContext> starting = CompletableFuture.supplyAsync(() ->
                new SpringApplicationBuilder(ShopApplication.class).profiles("demo").run(
                        "--server.port=0",
                        "--spring.datasource.url=" + environment.postgres().getJdbcUrl(),
                        "--spring.datasource.username=" + environment.postgres().getUsername(),
                        "--spring.datasource.password=" + environment.postgres().getPassword(),
                        "--shop.tigerbeetle.address=" + environment.ledger().clientAddress(),
                        "--shop.demo.toxiproxy-url=" + environment.ledger().controlUrl()));

        // then
        try (ConfigurableApplicationContext app = assertStartsWithin(starting, Duration.ofSeconds(60))) {
            assertLinkConnected(app);
        }
    }

    // ── Effects checked only in this file ────────────────────────────────

    private ConfigurableApplicationContext assertStartsWithin(CompletableFuture<ConfigurableApplicationContext> starting,
            Duration limit) throws Exception {
        try {
            return starting.get(limit.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // let the stuck startup finish so its threads end with the test
            environment.ledger().restoreConnection();
            starting.get(limit.toSeconds(), TimeUnit.SECONDS).close();
            throw new AssertionError("The app did not finish starting within " + limit + " while the connection was cut", e);
        }
    }

    private static void assertLinkConnected(ConfigurableApplicationContext app) {
        assertThat(app.getBean(LedgerLink.class).connected()).as("TB connection after startup").isTrue();
    }
}
