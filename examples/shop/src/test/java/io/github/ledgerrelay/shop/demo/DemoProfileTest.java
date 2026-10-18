package io.github.ledgerrelay.shop.demo;

import static io.github.ledgerrelay.shop.ShopAssertions.assertAnswered;

import io.github.ledgerrelay.shop.ShopSpringTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@DisplayName("Demo controls exist only in the demo profile")
class DemoProfileTest extends ShopSpringTest {

    @Autowired
    MockMvcTester http;

    @Test
    @DisplayName("Without the demo profile, bad post injection, TB cut and burst answer 404")
    void demoRoutesAreClosedOutsideTheDemoProfile() {
        // when
        MvcTestResult badPost = http.post().uri("/api/demo/bad-post").exchange();
        MvcTestResult cut = http.post().uri("/api/demo/ledger/cut").exchange();
        MvcTestResult burst = http.post().uri("/api/demo/burst?count=1").exchange();
        MvcTestResult link = http.get().uri("/api/demo/ledger").exchange();

        // then
        assertAnswered(badPost, HttpStatus.NOT_FOUND);
        assertAnswered(cut, HttpStatus.NOT_FOUND);
        assertAnswered(burst, HttpStatus.NOT_FOUND);
        assertAnswered(link, HttpStatus.NOT_FOUND);
    }
}
