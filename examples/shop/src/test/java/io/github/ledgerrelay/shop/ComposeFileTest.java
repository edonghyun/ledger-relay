package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the compose.yaml at the repository root must keep. Reads the file only; ShopComposeTest starts it. */
@DisplayName("compose.yaml — the demo shows no false numbers after a restart")
class ComposeFileTest {

    static final Path COMPOSE = Path.of("../../compose.yaml");
    static final Path DASHBOARD = Path.of("compose/grafana/dashboards/ledger-relay.json");

    @Test
    @DisplayName("Neither PostgreSQL nor TigerBeetle keeps data in a named volume — so that one store never outlives down without the other and shows orders charged twice")
    void neitherStoreOutlivesTheOther() throws IOException {
        // given
        List<String> lines = Files.readAllLines(COMPOSE);

        // then: a top-level volumes: entry would survive down (without -v)
        assertNoNamedVolumes(lines);
    }

    @Test
    @DisplayName("The PostgreSQL and TigerBeetle images match the versions the tests use — so that a version bump in the catalog does not forget the demo")
    void imagesMatchTheVersionCatalog() throws IOException {
        // given
        String compose = Files.readString(COMPOSE);

        // then
        assertUsesImage(compose, "postgres:" + System.getProperty("ledgerrelay.postgres.version"));
        assertUsesImage(compose, "ghcr.io/tigerbeetle/tigerbeetle:" + System.getProperty("ledgerrelay.tigerbeetle.version"));
    }

    @Test
    @DisplayName("Prometheus and Grafana are pinned to exact versions — so the demo looks the same on every machine")
    void monitoringImagesArePinned() throws IOException {
        // given
        String compose = Files.readString(COMPOSE);

        // then
        assertUsesImage(compose, "prom/prometheus:v3.5.0");
        assertUsesImage(compose, "grafana/grafana:12.2.0");
    }

    @Test
    @DisplayName("The dashboard file is valid JSON with the uid the shop links to and at least four panels")
    void theDashboardFileIsValid() throws IOException {
        // given
        String dashboard = Files.readString(DASHBOARD);

        // when
        String uid = JsonPath.read(dashboard, "$.uid");
        List<Object> panels = JsonPath.read(dashboard, "$.panels[?(@.type != 'row')]");

        // then
        assertThat(uid).as("dashboard uid").isEqualTo("ledger-relay");
        assertThat(panels).as("dashboard panels").hasSizeGreaterThanOrEqualTo(4);
    }

    // ── Effects checked only in this file ────────────────────────────────

    private static void assertNoNamedVolumes(List<String> lines) {
        assertThat(lines).as("top-level volumes declaration").noneMatch(line -> line.startsWith("volumes:"));
    }

    private static void assertUsesImage(String compose, String image) {
        assertThat(compose).as("compose.yaml").contains("image: " + image + "\n");
    }
}
