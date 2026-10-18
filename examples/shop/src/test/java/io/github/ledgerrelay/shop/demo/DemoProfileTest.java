package io.github.ledgerrelay.shop.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.shop.ShopSpringTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@DisplayName("Demo controls exist only in the demo profile")
class DemoProfileTest extends ShopSpringTest {

    @Autowired
    MockMvcTester http;

    @Test
    @DisplayName("Without the demo profile, bad post injection, TB cut and burst answer 404")
    void demoRoutesAreClosedOutsideTheDemoProfile() {
        assertThat(http.post().uri("/api/demo/bad-post")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.post().uri("/api/demo/ledger/cut")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.post().uri("/api/demo/burst?count=1")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.get().uri("/api/demo/ledger")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
