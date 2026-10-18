package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.spi.OrderingKeys;
import java.util.List;
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

    /**
     * 넘길 키를 고른다. 끝났는데 맨 앞인 명령({@code ledger_command_finished_head})의 키 행을 잠근다. 다른 트랜잭션이 쥔 키
     * 행은 건너뛰며, 건너뛴 행은 limit에 세지 않는다. 테스트가 실행 계획을 본다.
     */
    static final String LOCK_KEYS_TO_PASS_ON = """
                SELECT k.ordering_key
                FROM ledger_command c
                JOIN ledger_ordering_key k ON k.ordering_key = c.ordering_key
                WHERE c.is_head AND c.state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED')
                ORDER BY c.id
                LIMIT :limit
                FOR UPDATE OF k SKIP LOCKED
            """;

    /**
     * 끝난 명령이 쥔 맨 앞을 다음 미완료로 넘긴다(02e P2e-7). 잡은 키 행은 값을 그대로 두고 갱신한다. 새 행 버전이 생기므로,
     * 이보다 앞서 스냅숏을 잡은 REPEATABLE READ 이상의 앱 트랜잭션은 이 키에서 순번을 받을 때 직렬화 실패(40001)로 끝나
     * 재시도한다. 그러지 않으면 그 앱은 옛 스냅숏에서 아직 맨 앞이 있다고 보고 새 명령을 맨 앞으로 세우지 않아, 그 키가
     * 영원히 나가지 않는다. 다음 미완료를 접수하는 중인 트랜잭션은 순번을 받으며 키 행을 쥐었으므로 그 키는 건너뛰고, 그 접수가
     * 커밋한 뒤 다음 호출이 넘긴다.
     */
    @Override
    public void passOnFinishedHeads(int limit) {
        List<String> keys = sql.sql(LOCK_KEYS_TO_PASS_ON).param("limit", limit).query(String.class).list();
        if (keys.isEmpty()) {
            return;
        }
        sql.sql("UPDATE ledger_ordering_key SET next_seq = next_seq WHERE ordering_key IN (:keys)")
                .param("keys", keys).update();
        sql.sql(CLEAR_FINISHED_HEADS).param("keys", keys).update();
        sql.sql(MARK_NEXT_HEADS).param("keys", keys).update();
    }

    static final String CLEAR_FINISHED_HEADS = """
                UPDATE ledger_command SET is_head = false
                WHERE ordering_key IN (:keys) AND is_head AND state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED')
            """;

    /**
     * 키마다 가장 앞 순번의 미완료를 맨 앞으로 켠다. 키마다 따로 {@code min(seq)}를 구해야 미완료 인덱스에서 하나만 읽는다.
     * 키들을 묶어 {@code GROUP BY}로 구하면 막힌 키 뒤의 백로그를 모두 읽는다(2만 건에 564페이지). 테스트가 실행 계획을 본다.
     */
    static final String MARK_NEXT_HEADS = """
                UPDATE ledger_command c SET is_head = true
                FROM ledger_ordering_key k
                WHERE k.ordering_key IN (:keys) AND c.ordering_key = k.ordering_key
                  AND c.seq = (SELECT min(u.seq) FROM ledger_command u
                               WHERE u.ordering_key = k.ordering_key AND u.state IN ('PENDING','CLAIMED','HALTED'))
            """;
}
