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
 * 라이브러리 스키마와 샘플 앱 스키마를 적용한다(03b §7.5). 라이브러리는 스키마를 자동 적용하지 않는다. 이것은 샘플 앱만의 편의다.
 *
 * <p>compose는 앱 둘을 같은 DB에 동시에 띄운다. 둘이 함께 {@code CREATE TABLE}을 하면 한쪽이 실패해 기동하지 못한다
 * (docs/failure-modes.md F37). 그래서 한 트랜잭션에서 advisory lock을 잡고, 표가 없을 때만 적용한다. 늦은 쪽은 앞쪽이 커밋할
 * 때까지 lock에서 기다렸다가 표가 있는 것을 보고 그냥 지나간다.
 */
public final class ShopSchema {

    /** 이 앱의 advisory lock 번호. 바트의 ISO 숫자 코드다. */
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
            }
        });
    }

    private static String read(String resource) {
        try (InputStream in = ShopSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + "가 클래스패스에 없습니다");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
