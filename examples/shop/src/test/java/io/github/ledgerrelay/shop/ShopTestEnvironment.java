package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.tigerbeetle.testing.ProxiedTigerBeetle;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 샘플 앱 테스트가 함께 쓰는 PostgreSQL과 Toxiproxy 뒤의 TigerBeetle. JVM당 한 번 띄우고 JVM이 끝날 때 Testcontainers가
 * 치운다. 테스트끼리 같은 DB와 원장을 쓰므로, 잔액을 보는 테스트는 자기만 쓰는 구매자나 주문을 쓴다.
 *
 * <p>정합성을 일부러 깨는 테스트는 {@link #isolated()}로 자기 환경을 따로 띄운다.
 */
public final class ShopTestEnvironment {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static ShopTestEnvironment shared;

    private final PostgreSQLContainer postgres;
    private final ProxiedTigerBeetle ledger;

    private ShopTestEnvironment() {
        postgres = new PostgreSQLContainer("postgres:18.6");
        postgres.start();
        ledger = ProxiedTigerBeetle.start();
    }

    public static synchronized ShopTestEnvironment shared() {
        if (shared == null) {
            shared = new ShopTestEnvironment();
        }
        return shared;
    }

    /** 다른 테스트와 나누지 않는 새 PG와 TB. 부른 쪽이 {@link #close()}한다. */
    public static ShopTestEnvironment isolated() {
        return new ShopTestEnvironment();
    }

    public PostgreSQLContainer postgres() {
        return postgres;
    }

    public ProxiedTigerBeetle ledger() {
        return ledger;
    }

    /** 새 TB 클라이언트. 부른 쪽이 닫는다. */
    public Client newLedgerClient() {
        return ledger.newClient();
    }

    /** 같은 PG 서버에 빈 database를 하나 더 만든다. 스키마가 없는 상태에서 시작하는 테스트용. 부른 쪽이 닫는다. */
    public HikariDataSource newEmptyDatabase() {
        String name = "empty_" + DATABASES.incrementAndGet();
        try (HikariDataSource admin = dataSource(postgres.getJdbcUrl())) {
            JdbcClient.create(admin).sql("CREATE DATABASE " + name).update();
        }
        return dataSource(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
    }

    /** {@code @DynamicPropertySource}에서 부른다. demo 프로필 테스트가 TB를 끊을 수 있게 Toxiproxy 주소도 넘긴다. */
    public void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("shop.tigerbeetle.address", ledger::clientAddress);
        registry.add("shop.demo.toxiproxy-url", ledger::controlUrl);
    }

    public void close() {
        ledger.close();
        postgres.stop();
    }

    private HikariDataSource dataSource(String url) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(4);
        return dataSource;
    }
}
