package io.github.ledgerrelay.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import io.github.ledgerrelay.jdbc.testing.PostgresTestDatabase;
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
 * One set of PostgreSQL storage. There is one container per JVM; it starts on first use and the schema is applied once.
 * One connection pool is created per default isolation level and reused. Tests are isolated with {@link #clear()}.
 */
final class PostgresStorage implements StorageUnderTest {

    /** Sets no default isolation level on the pool: the driver and server default (READ COMMITTED) applies */
    static final String SERVER_DEFAULT = "";

    private static final PostgreSQLContainer CONTAINER = startContainer();
    private static final Map<String, HikariDataSource> POOLS = new ConcurrentHashMap<>();

    private static volatile EntityManagerFactory jpa;

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTransactions transactions;
    private final JdbcOrderingKeys keys;
    private final JdbcCommandStore store;

    private PostgresStorage(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.transactions = new JdbcTransactions(transactionManager, dataSource);
        this.keys = new JdbcOrderingKeys(dataSource);
        this.store = new JdbcCommandStore(dataSource);
    }

    /** For apps that use a DataSource transaction manager. Returns an emptied store. */
    static PostgresStorage withDataSourceTransactions() {
        return withDataSourceTransactions(SERVER_DEFAULT);
    }

    /**
     * With a default isolation level set on the pool. The value is a Hikari {@code transactionIsolation} name (for
     * example {@code TRANSACTION_REPEATABLE_READ}).
     */
    static PostgresStorage withDataSourceTransactions(String poolIsolation) {
        DataSource dataSource = pool(poolIsolation);
        PostgresStorage storage = new PostgresStorage(dataSource, new DataSourceTransactionManager(dataSource));
        storage.clear();
        return storage;
    }

    /**
     * For apps that use a JPA transaction manager: Hibernate and {@link JpaTransactionManager} on the same DataSource.
     * {@code JdbcClient} joins the same transaction only if the manager knows the DataSource and
     * {@code HibernateJpaDialect} exposes the JDBC connection. It has an app table {@code orders} and one entity.
     */
    static PostgresStorage withJpaTransactions() {
        return withJpaTransactions(new HibernateJpaDialect());
    }

    /** For control experiments: replaces the manager's JPA dialect. */
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

    /**
     * A DataSource that keeps using a single connection. Used to check how server-side prepared statements (pgjdbc
     * prepareThreshold) accumulate on that connection. The caller closes it with {@code destroy()}.
     */
    static org.springframework.jdbc.datasource.SingleConnectionDataSource singleConnection() {
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource(CONTAINER.getJdbcUrl(),
                CONTAINER.getUsername(), CONTAINER.getPassword(), true);
    }

    /** Control experiment: a dialect that accepts the isolation level but does not expose the JDBC connection */
    static final class NonExposingJpaDialect extends org.springframework.orm.jpa.DefaultJpaDialect {
        @Override
        public Object beginTransaction(EntityManager entityManager,
                org.springframework.transaction.TransactionDefinition definition) {
            entityManager.getTransaction().begin();
            return null;
        }
    }

    /** The EntityManager that app code uses: it uses the one of the current transaction. */
    EntityManager sharedEntityManager() {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory(dataSource));
    }

    @Override
    public OrderingKeys keys() {
        return keys;
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
    public boolean allowsMalformedRows() {
        return false;
    }

    @Override
    public void deleteCommand(String commandId) {
        int deleted = sql().sql("DELETE FROM ledger_command WHERE command_id = :id").param("id", commandId).update();
        if (deleted != 1) {
            throw new IllegalStateException("given setup failed: no command " + commandId + " to delete");
        }
        rebuildHeads();
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

    /** PG {@code now()} advances with real time in each transaction */
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

    /**
     * Recomputes the head marks and the key rows from the command rows. Called after setup that changes rows with
     * plain SQL. The script's table lock works only inside a transaction, so it opens one or joins the open one.
     */
    void rebuildHeads() {
        transactions.inTransaction(() -> sql().sql(resource("rebuild-heads.sql")).update());
    }

    /**
     * Checks per key that the mark matches the rows: there are 0 or 1 marks. If there are unfinished commands, there is
     * a mark, and it is on the first unfinished command or on a finished command with a lower sequence number (which
     * the next claim passes on). If there are none, there is no mark or it is on a finished command. A key with
     * commands has a key row (passing on locks that row). Throws with all keys that do not match.
     */
    void assertHeadsConsistent() {
        java.util.List<String> broken = sql().sql("""
                WITH actual AS (
                    SELECT ordering_key,
                           min(seq) FILTER (WHERE state IN ('PENDING','CLAIMED','HALTED')) AS first_unfinished,
                           count(*) FILTER (WHERE is_head) AS heads,
                           min(seq) FILTER (WHERE is_head) AS head_seq,
                           bool_or(is_head AND state IN ('PENDING','CLAIMED','HALTED')) AS head_unfinished
                    FROM ledger_command GROUP BY ordering_key)
                SELECT a.ordering_key || ': ' || a.heads || ' head marks (seq ' || coalesce(a.head_seq::text, '-')
                       || CASE WHEN a.head_unfinished THEN ', unfinished' ELSE ', finished' END
                       || '), first unfinished seq ' || coalesce(a.first_unfinished::text, '-')
                       || CASE WHEN k.ordering_key IS NULL THEN ', no key row' ELSE '' END
                FROM actual a LEFT JOIN ledger_ordering_key k ON k.ordering_key = a.ordering_key
                WHERE a.heads > 1
                   OR k.ordering_key IS NULL
                   OR (a.first_unfinished IS NOT NULL AND (a.heads = 0
                        OR (a.head_unfinished AND a.head_seq <> a.first_unfinished)
                        OR (NOT a.head_unfinished AND a.head_seq > a.first_unfinished)))
                   OR (a.first_unfinished IS NULL AND a.head_unfinished)
                ORDER BY 1
                """).query(String.class).list();
        if (!broken.isEmpty()) {
            throw new AssertionError("Keys whose head marks do not match the rows: " + broken);
        }
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
        PostgreSQLContainer container = PostgresTestDatabase.container();
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        try (HikariDataSource once = new HikariDataSource()) {
            once.setJdbcUrl(container.getJdbcUrl());
            once.setUsername(container.getUsername());
            once.setPassword(container.getPassword());
            PostgresTestDatabase.applySchema(once);
        }
        return container;
    }

    private static String resource(String name) {
        try (InputStream in = JdbcCommandStore.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
