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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
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

    private static volatile EntityManagerFactory jpa;

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTransactions transactions;
    private final JdbcCommandStore store;

    private PostgresStorage(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.transactions = new JdbcTransactions(transactionManager, dataSource);
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

    /**
     * 앱이 JPA 트랜잭션 매니저를 쓰는 경우(R16, P2b-13). 같은 DataSource 위의 Hibernate와 {@link JpaTransactionManager}다.
     * 매니저가 DataSource를 알고 {@code HibernateJpaDialect}가 JDBC 커넥션을 노출해야 {@code JdbcClient}가 같은 트랜잭션에
     * 합류한다. 앱 테이블 {@code orders}와 엔터티 하나를 둔다.
     */
    static PostgresStorage withJpaTransactions() {
        return withJpaTransactions(new HibernateJpaDialect());
    }

    /** 대조군용: 매니저의 JPA dialect를 바꾼다. */
    static PostgresStorage withJpaTransactions(JpaDialect dialect) {
        DataSource dataSource = pool(SERVER_DEFAULT);
        JpaTransactionManager manager = new JpaTransactionManager(entityManagerFactory(dataSource));
        manager.setDataSource(dataSource);
        manager.setJpaDialect(dialect);
        PostgresStorage storage = new PostgresStorage(dataSource, manager);
        storage.clear();
        storage.sql().sql("TRUNCATE orders").update();
        return storage;
    }

    /** 대조군: 격리 수준은 받아들이지만 JDBC 커넥션을 노출하지 않는 dialect */
    static final class NonExposingJpaDialect extends org.springframework.orm.jpa.DefaultJpaDialect {
        @Override
        public Object beginTransaction(EntityManager entityManager,
                org.springframework.transaction.TransactionDefinition definition) {
            entityManager.getTransaction().begin();
            return null;
        }
    }

    /** 앱 코드가 쓰는 EntityManager: 지금 트랜잭션의 것을 쓴다. */
    EntityManager sharedEntityManager() {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory(dataSource));
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
    public void deleteCommand(String commandId) {
        int deleted = sql().sql("DELETE FROM ledger_command WHERE command_id = :id").param("id", commandId).update();
        if (deleted != 1) {
            throw new IllegalStateException("given 준비 실패: 지울 " + commandId + "가 없다");
        }
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

    private static synchronized EntityManagerFactory entityManagerFactory(DataSource dataSource) {
        if (jpa == null) {
            JdbcClient.create(dataSource).sql("CREATE TABLE IF NOT EXISTS orders (id text PRIMARY KEY)").update();
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(OrderEntity.class.getName()));
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            factory.afterPropertiesSet();
            jpa = factory.getObject();
        }
        return jpa;
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
