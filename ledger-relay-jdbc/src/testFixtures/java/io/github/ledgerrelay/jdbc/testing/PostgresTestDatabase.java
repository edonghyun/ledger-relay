package io.github.ledgerrelay.jdbc.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL container and ledger-relay schema for tests. Gradle passes the image version from the version catalog
 * (gradle/libs.versions.toml) as a system property. The jdbc and spring-boot modules and the sample app tests share it.
 */
public final class PostgresTestDatabase {

    static final String VERSION_PROPERTY = "ledgerrelay.postgres.version";

    private PostgresTestDatabase() {
    }

    /** A container that is not started yet. The caller starts and stops it. */
    public static PostgreSQLContainer container() {
        String version = System.getProperty(VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("The " + VERSION_PROPERTY + " system property is missing. The Gradle test task "
                    + "must pass the value from the version catalog (ledger-relay.java-conventions in buildSrc).");
        }
        return new PostgreSQLContainer("postgres:" + version);
    }

    /** The library schema ({@code io/github/ledgerrelay/jdbc/schema.sql} in the jdbc jar). Apps apply it with their
     * migration tool. */
    public static String schema() {
        try (InputStream in = PostgresTestDatabase.class.getResourceAsStream("/io/github/ledgerrelay/jdbc/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void applySchema(DataSource dataSource) {
        JdbcClient.create(dataSource).sql(schema()).update();
    }
}
