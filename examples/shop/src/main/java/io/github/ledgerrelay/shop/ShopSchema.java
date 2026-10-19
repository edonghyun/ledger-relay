package io.github.ledgerrelay.shop;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the library schema and the sample app schema. The library never applies its schema automatically; this is a
 * convenience of the sample app only.
 *
 * <p>compose starts two apps against the same DB at the same time. If both run {@code CREATE TABLE} together, one of
 * them fails and does not start (docs/failure-modes.md F37). So the schema is applied in one transaction that holds an
 * advisory lock, and only when the tables are missing. The later app waits on the lock until the first one commits,
 * then sees the tables and moves on.
 *
 * <p>When the tables exist but were created by an older version of the sample app, startup stops with what to do
 * (F47). Starting on them would make every result write fail and retry forever. The demo is meant to start from an
 * empty DB and ledger, so there are no migrations.
 */
public final class ShopSchema {

    /** Advisory lock number of this app: the ISO numeric code of the baht. */
    static final long LOCK = 764;
    private static final String LIBRARY_SCHEMA = "/io/github/ledgerrelay/jdbc/schema.sql";
    private static final String SHOP_SCHEMA = "/shop-schema.sql";

    private ShopSchema() {
    }

    public static void apply(DataSource dataSource) {
        JdbcClient sql = JdbcClient.create(dataSource);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            sql.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK).query().singleRow();
            boolean present = sql.sql("SELECT to_regclass('shop_order') IS NOT NULL").query(Boolean.class).single();
            if (!present) {
                sql.sql(read(LIBRARY_SCHEMA)).update();
                sql.sql(read(SHOP_SCHEMA)).update();
            } else if (!current(sql)) {
                throw new IllegalStateException("The database was created by an older version of the sample shop. "
                        + "Start the demo from empty: docker compose down, then docker compose up --build");
            }
        });
    }

    /** The newest column the shop schema added (03c). An older database does not have it. */
    private static boolean current(JdbcClient sql) {
        return sql.sql("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_name = 'shop_order_command' AND column_name = 'final_state'
                                 AND table_schema = current_schema())
                """).query(Boolean.class).single();
    }

    private static String read(String resource) {
        try (InputStream in = ShopSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
