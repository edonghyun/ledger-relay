package io.github.ledgerrelay.jdbc.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트용 PostgreSQL 컨테이너와 ledger-relay 스키마. 이미지 버전은 Gradle이 버전 목록(gradle/libs.versions.toml)에서 시스템
 * 프로퍼티로 넘긴다. jdbc, spring-boot 모듈과 샘플 앱의 테스트가 같이 쓴다.
 */
public final class PostgresTestDatabase {

    static final String VERSION_PROPERTY = "ledgerrelay.postgres.version";

    private PostgresTestDatabase() {
    }

    /** 띄우지 않은 컨테이너. 부른 쪽이 start/stop한다. */
    public static PostgreSQLContainer container() {
        String version = System.getProperty(VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(VERSION_PROPERTY + " 시스템 프로퍼티가 없습니다. Gradle test 태스크가 버전 목록의 값을 "
                    + "넘겨야 합니다(buildSrc의 ledger-relay.java-conventions).");
        }
        return new PostgreSQLContainer("postgres:" + version);
    }

    /** 라이브러리 스키마(jdbc jar의 {@code io/github/ledgerrelay/jdbc/schema.sql}). 앱은 이것을 마이그레이션 도구로 적용한다. */
    public static String schema() {
        try (InputStream in = PostgresTestDatabase.class.getResourceAsStream("/io/github/ledgerrelay/jdbc/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql이 클래스패스에 없습니다");
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
