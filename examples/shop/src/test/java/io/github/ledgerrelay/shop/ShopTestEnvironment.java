package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.jdbc.testing.PostgresTestDatabase;
import io.github.ledgerrelay.tigerbeetle.testing.ProxiedTigerBeetle;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and TigerBeetle behind Toxiproxy, shared by the sample app tests. Started once per JVM; Testcontainers
 * removes them when the JVM exits. Tests share the DB and the ledger, so a test that checks balances uses a buyer or
 * order of its own.
 *
 * <p>Tests that break consistency on purpose start their own environment with {@link #isolated()}.
 */
public final class ShopTestEnvironment {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static ShopTestEnvironment shared;

    private final PostgreSQLContainer postgres;
    private final ProxiedTigerBeetle ledger;

    private ShopTestEnvironment() {
        postgres = PostgresTestDatabase.container();
        postgres.start();
        ledger = ProxiedTigerBeetle.start();
    }

    public static synchronized ShopTestEnvironment shared() {
        if (shared == null) {
            shared = new ShopTestEnvironment();
        }
        return shared;
    }

    /** New PG and TB not shared with other tests. The caller calls {@link #close()}. */
    public static ShopTestEnvironment isolated() {
        return new ShopTestEnvironment();
    }

    public PostgreSQLContainer postgres() {
        return postgres;
    }

    public ProxiedTigerBeetle ledger() {
        return ledger;
    }

    /** New TB client. The caller closes it. */
    public Client newLedgerClient() {
        return ledger.newClient();
    }

    /** Creates another empty database on the same PG server, for tests that start without a schema. The caller closes it. */
    public HikariDataSource newEmptyDatabase() {
        String name = "empty_" + DATABASES.incrementAndGet();
        try (HikariDataSource admin = dataSource(postgres.getJdbcUrl())) {
            JdbcClient.create(admin).sql("CREATE DATABASE " + name).update();
        }
        return dataSource(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
    }

    /** Called from {@code @DynamicPropertySource}. Also passes the Toxiproxy address so that demo profile tests can cut TB. */
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
