package io.github.ledgerrelay.shop.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.shop.ShopSpringTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@DisplayName("데모 조작은 demo 프로필에서만 열린다 (03b §7.4)")
class DemoProfileTest extends ShopSpringTest {

    @Autowired
    MockMvcTester http;

    @Test
    @DisplayName("demo 프로필이 아니면 잘못된 확정 넣기, TB 끊기, 몰아넣기가 404다")
    void demoRoutesAreClosedOutsideTheDemoProfile() {
        assertThat(http.post().uri("/api/demo/bad-post")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.post().uri("/api/demo/ledger/cut")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.post().uri("/api/demo/burst?count=1")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.get().uri("/api/demo/ledger")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
