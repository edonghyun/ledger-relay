package io.github.ledgerrelay.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ledger-relay sample shop. Payment is a reservation (authorize), shipping posts it (capture), cancelling voids it. */
@SpringBootApplication
@EnableConfigurationProperties(ShopProperties.class)
@EnableScheduling
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
