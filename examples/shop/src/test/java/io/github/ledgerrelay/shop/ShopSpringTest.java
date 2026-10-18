package io.github.ledgerrelay.shop;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One app, worker included, on the shared PG and TB. Test classes with the same configuration share the Spring context. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ShopSpringTest {

    @DynamicPropertySource
    static void sharedEnvironment(DynamicPropertyRegistry registry) {
        ShopTestEnvironment.shared().properties(registry);
    }
}
