package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.spi.OrderingKeys;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL 순서 키 저장소. {@code ledger_ordering_key} 행을 잠그는 SQL은 모두 여기에 있다. {@link JdbcCommandStore}는
 * 이 행을 잠그지 않는다(02e P2e-7).
 */
public final class JdbcOrderingKeys implements OrderingKeys {

    private final JdbcClient sql;

    public JdbcOrderingKeys(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * 키 행을 upsert해 잠그고 다음 순번을 낸다(P2b-8). 잠금은 커밋까지 유지되므로 같은 키의 다음 접수는 이 트랜잭션이
     * 끝날 때까지 기다린다. 그래서 seq 순서가 커밋 순서와 같다(R12).
     */
    @Override
    public long lockKeyAndTakeSeq(String orderingKey) {
        return sql.sql("""
                INSERT INTO ledger_ordering_key (ordering_key, next_seq) VALUES (:key, 2)
                ON CONFLICT (ordering_key) DO UPDATE SET next_seq = ledger_ordering_key.next_seq + 1
                RETURNING next_seq - 1
                """).param("key", orderingKey).query(Long.class).single();
    }

    @Override
    public void passOnFinishedHeads(int limit) {
    }
}
