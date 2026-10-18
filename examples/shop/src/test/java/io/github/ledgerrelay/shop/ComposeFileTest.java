package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("Neither PostgreSQL nor TigerBeetle keeps data in a named volume — so that one store never outlives down without the other and shows double transfers")
    void neitherStoreOutlivesTheOther() throws IOException {
        // given
        List<String> lines = Files.readAllLines(COMPOSE);

        // then: a top-level volumes: entry would survive down (without -v)
        assertThat(lines).as("top-level volumes declaration").noneMatch(line -> line.startsWith("volumes:"));
    }

    @Test
    @DisplayName("The PostgreSQL and TigerBeetle images match the versions the tests use — so that a version bump in the catalog does not forget the demo")
    void imagesMatchTheVersionCatalog() throws IOException {
        // given
        String compose = Files.readString(COMPOSE);

        // then
        assertThat(compose).as("compose.yaml")
                .contains("image: postgres:" + System.getProperty("ledgerrelay.postgres.version") + "\n")
                .contains("image: ghcr.io/tigerbeetle/tigerbeetle:" + System.getProperty("ledgerrelay.tigerbeetle.version")
                        + "\n");
    }
}
