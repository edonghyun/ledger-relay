package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.spi.OrderingKeys;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL ordering key store. All SQL that locks {@code ledger_ordering_key} rows lives here.
 * {@link JdbcCommandStore} does not lock these rows.
 */
public final class JdbcOrderingKeys implements OrderingKeys {

    private final JdbcClient sql;

    public JdbcOrderingKeys(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * Upserts and locks the key row and returns the next sequence number. The lock is held until commit, so the next
     * acceptance on the same key waits until this transaction ends. That is why seq order equals commit order.
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
     * Picks the keys whose head to pass on. Locks the key rows of commands that are finished but still the head
     * ({@code ledger_command_finished_head}). Key rows held by another transaction are skipped, and skipped rows do not
     * count toward the limit. The finished head rows themselves are not locked: if someone holds such a row (for
     * example a reservation row, F13), passing on the whole key would be delayed. So the query can also pick a key that
     * another worker passed on and committed after this query's snapshot. In that case {@link #MARK_NEXT_HEADS} sees
     * the existing head and does nothing. A test checks the query plan.
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
     * Passes the head held by a finished command on to the next unfinished command. The locked key rows are updated
     * with their values unchanged. This creates a new row version, so an app transaction at REPEATABLE READ or higher
     * whose snapshot is older fails with a serialization failure (40001) when it takes a sequence number on this key,
     * and retries. Otherwise that app would see a head in its old snapshot, would not make the new command the head,
     * and the key would never move again. A transaction that is accepting the next unfinished command holds the key
     * row since it took a sequence number, so that key is skipped, and the next call passes it on after that
     * acceptance commits.
     */
    @Override
    public void passOnFinishedHeads(int limit) {
        List<String> keys = sql.sql(LOCK_KEYS_TO_PASS_ON).param("limit", limit).query(String.class).list();
        if (keys.isEmpty()) {
            return;
        }
        sql.sql(REWRITE_KEY_ROWS).param("keys", keys).update();
        sql.sql(CLEAR_FINISHED_HEADS).param("keys", keys).update();
        sql.sql(MARK_NEXT_HEADS).param("keys", keys).update();
    }

    static final String REWRITE_KEY_ROWS = """
                UPDATE ledger_ordering_key SET next_seq = next_seq WHERE ordering_key IN (:keys)
            """;

    static final String CLEAR_FINISHED_HEADS = """
                UPDATE ledger_command SET is_head = false
                WHERE ordering_key IN (:keys) AND is_head AND state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED')
            """;

    /**
     * For each key, marks the unfinished command with the lowest sequence number as the head. Computing
     * {@code min(seq)} separately per key reads only one entry from the unfinished index. Computing it for all keys
     * with {@code GROUP BY} reads the whole backlog behind a blocked key (564 pages for 20,000 rows). Keys that already
     * have a head are left alone: that head may be writing its result, and waiting to update its row would deadlock
     * with the handler. A test checks the query plan.
     */
    static final String MARK_NEXT_HEADS = """
                UPDATE ledger_command c SET is_head = true
                FROM ledger_ordering_key k
                WHERE k.ordering_key IN (:keys) AND c.ordering_key = k.ordering_key
                  AND c.seq = (SELECT min(u.seq) FROM ledger_command u
                               WHERE u.ordering_key = k.ordering_key AND u.state IN ('PENDING','CLAIMED','HALTED'))
                  AND NOT EXISTS (SELECT 1 FROM ledger_command h WHERE h.ordering_key = k.ordering_key AND h.is_head)
            """;
}
