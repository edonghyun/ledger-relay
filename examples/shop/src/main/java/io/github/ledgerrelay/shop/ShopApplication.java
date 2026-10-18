package io.github.ledgerrelay.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ledger-relay 샘플 쇼핑몰. 결제는 예약(승인), 출고 때 확정(매입), 취소는 해제다. */
@SpringBootApplication
@EnableConfigurationProperties(ShopProperties.class)
@EnableScheduling
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
