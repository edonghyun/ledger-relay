package io.github.ledgerrelay.shop.status;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Numbers for the tiles and pipeline stages of the UI. Counts the library's command table directly; at demo size one
 * query is enough. This is where production metrics would later move to Micrometer (docs/development.md).
 */
@Component
class CommandStats {

    record Counts(long accepted, long completed, long pending, long claimed, long applied, long halted, Long p99Millis) {

        long backlog() {
            return pending + claimed + halted;
        }
    }

    private static final String COUNTS = """
            SELECT count(*) AS accepted,
                   count(*) FILTER (WHERE state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED')) AS completed,
                   count(*) FILTER (WHERE state = 'PENDING') AS pending,
                   count(*) FILTER (WHERE state = 'CLAIMED') AS claimed,
                   count(*) FILTER (WHERE state = 'APPLIED') AS applied,
                   count(*) FILTER (WHERE state = 'HALTED') AS halted
            FROM ledger_command
            """;
    /** p99 of accept → completion time over the last 1,000 completed commands, in milliseconds. */
    private static final String P99 = """
            SELECT round(1000 * percentile_cont(0.99) WITHIN GROUP (ORDER BY took))
            FROM (SELECT extract(epoch FROM completed_at - accepted_at) AS took FROM ledger_command
                  WHERE completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT 1000) recent
            """;

    private final JdbcClient sql;

    CommandStats(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    Counts counts() {
        Long p99 = sql.sql(P99).query(Long.class).optional().orElse(null);
        return sql.sql(COUNTS).query((row, n) -> new Counts(row.getLong("accepted"), row.getLong("completed"),
                row.getLong("pending"), row.getLong("claimed"), row.getLong("applied"), row.getLong("halted"), p99)).single();
    }

    /**
     * Posts sent to TB whose result is not written yet. Meanwhile the TB balance can run ahead of the PG sum of paid
     * orders.
     */
    long capturesInFlight() {
        return sql.sql("SELECT count(*) FROM ledger_command WHERE kind = 'POST' AND state = 'CLAIMED'")
                .query(Long.class).single();
    }

    long paidOrdersSatang() {
        return sql.sql("SELECT coalesce(sum(amount_satang), 0) FROM shop_order WHERE state = 'PAID'").query(Long.class)
                .single();
    }
}
