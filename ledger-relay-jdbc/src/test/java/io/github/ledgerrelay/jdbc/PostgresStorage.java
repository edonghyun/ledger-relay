package io.github.ledgerrelay.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 저장소 한 벌. 컨테이너는 JVM에 하나이며 처음 쓸 때 띄우고 스키마를 한 번 적용한다(P2b-15).
 * 커넥션 풀은 기본 격리 수준마다 하나씩 만들어 재사용한다. 격리는 테스트마다 {@link #clear()}로 한다.
 */
final class PostgresStorage implements StorageUnderTest {

    static final String IMAGE = "postgres:18.6";
    /** 풀의 기본 격리 수준을 정하지 않는다: 드라이버와 서버의 기본(READ COMMITTED)을 따른다 */
    static final String SERVER_DEFAULT = "";

    private static final PostgreSQLContainer CONTAINER = startContainer();
    private static final Map<String, HikariDataSource> POOLS = new ConcurrentHashMap<>();

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTransactions transactions;
    private final JdbcCommandStore store;

    private PostgresStorage(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.transactions = new JdbcTransactions(transactionManager);
        this.store = new JdbcCommandStore(dataSource);
    }

    /** 앱이 DataSource 트랜잭션 매니저를 쓰는 경우. 비운 저장소를 돌려준다. */
    static PostgresStorage withDataSourceTransactions() {
        return withDataSourceTransactions(SERVER_DEFAULT);
    }

    /**
     * 풀의 기본 격리 수준을 정한 경우(P2b-16 확인용). 값은 Hikari의 {@code transactionIsolation} 이름이다(예:
     * {@code TRANSACTION_REPEATABLE_READ}).
     */
    static PostgresStorage withDataSourceTransactions(String poolIsolation) {
        DataSource dataSource = pool(poolIsolation);
        PostgresStorage storage = new PostgresStorage(dataSource, new DataSourceTransactionManager(dataSource));
        storage.clear();
        return storage;
    }

    @Override
    public CommandStore store() {
        return store;
    }

    @Override
    public Transactions transactions() {
        return transactions;
    }

    @Override
    public void elapse(Duration duration) {
        sql().sql("""
                UPDATE ledger_command
                SET lease_expires_at = lease_expires_at - make_interval(secs => :seconds),
                    next_attempt_at  = next_attempt_at  - make_interval(secs => :seconds)
                """).param("seconds", duration.toNanos() / 1e9).update();
    }

    @Override
    public Class<? extends RuntimeException> outsideTransactionError() {
        return IllegalTransactionStateException.class;
    }

    @Override
    public boolean supportsRowLocks() {
        return true;
    }

    /** PG {@code now()}는 트랜잭션마다 실제 시간으로 흐른다 */
    @Override
    public boolean clockStandsStill() {
        return false;
    }

    DataSource dataSource() {
        return dataSource;
    }

    PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    JdbcClient sql() {
        return JdbcClient.create(dataSource);
    }

    void clear() {
        sql().sql("TRUNCATE ledger_command, ledger_ordering_key RESTART IDENTITY").update();
    }

    private static DataSource pool(String isolation) {
        return POOLS.computeIfAbsent(isolation, level -> {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(CONTAINER.getJdbcUrl());
            pool.setUsername(CONTAINER.getUsername());
            pool.setPassword(CONTAINER.getPassword());
            pool.setMaximumPoolSize(8);
            if (!level.isEmpty()) {
                pool.setTransactionIsolation(level);
            }
            return pool;
        });
    }

    private static PostgreSQLContainer startContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        try (HikariDataSource once = new HikariDataSource()) {
            once.setJdbcUrl(container.getJdbcUrl());
            once.setUsername(container.getUsername());
            once.setPassword(container.getPassword());
            JdbcClient.create(once).sql(schema()).update();
        }
        return container;
    }

    private static String schema() {
        try (InputStream in = JdbcCommandStore.class.getResourceAsStream("schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql을 찾지 못했다");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
