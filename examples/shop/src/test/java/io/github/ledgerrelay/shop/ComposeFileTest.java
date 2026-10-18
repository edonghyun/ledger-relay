package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 저장소 루트의 compose.yaml이 지켜야 하는 것. 띄우지 않고 파일만 읽는다(띄워서 보는 것은 ShopComposeTest). */
@DisplayName("compose.yaml — 데모가 다시 띄워도 거짓 숫자를 보이지 않는다")
class ComposeFileTest {

    static final Path COMPOSE = Path.of("../../compose.yaml");

    @Test
    @DisplayName("PostgreSQL과 TigerBeetle은 이름 붙은 볼륨에 남지 않는다 — down 뒤 한쪽만 남아 중복 이체가 보이지 않게")
    void neitherStoreOutlivesTheOther() throws IOException {
        // given
        List<String> lines = Files.readAllLines(COMPOSE);

        // then: 맨 위 volumes: 가 있으면 그 볼륨은 down(-v 없이) 뒤에도 남는다
        assertThat(lines).as("맨 위 volumes 선언").noneMatch(line -> line.startsWith("volumes:"));
    }

    @Test
    @DisplayName("PostgreSQL과 TigerBeetle 이미지는 테스트가 쓰는 버전과 같다 — 버전 목록 한 곳만 고치고 데모를 잊지 않게")
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
