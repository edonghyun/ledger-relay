package io.github.ledgerrelay.shop.status;

import io.github.ledgerrelay.core.CommandBacklog;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Numbers for the tiles and pipeline stages of the UI. Waiting commands come from the library's public backlog query;
 * accepted, finished and applied commands and the p99 come from the shop's own command table, which the outcome
 * handler fills in the transaction that writes each result. Both app instances share the DB, so these are totals for
 * both.
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
                   count(*) FILTER (WHERE completed_at IS NOT NULL) AS completed,
                   count(*) FILTER (WHERE final_state = 'APPLIED') AS applied
            FROM shop_order_command
            """;
    /** p99 of accept → completion time over the last 1,000 completed commands, in milliseconds. */
    private static final String P99 = """
            SELECT round(1000 * percentile_cont(0.99) WITHIN GROUP (ORDER BY took))
            FROM (SELECT extract(epoch FROM completed_at - accepted_at) AS took FROM shop_order_command
                  WHERE completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT 1000) recent
            """;

    private final JdbcClient sql;
    private final LedgerRelayOperator operator;

    CommandStats(DataSource dataSource, LedgerRelayOperator operator) {
        this.sql = JdbcClient.create(dataSource);
        this.operator = operator;
    }

    Counts counts() {
        CommandBacklog backlog = operator.backlog();
        Long p99 = sql.sql(P99).query(Long.class).optional().orElse(null);
        return sql.sql(COUNTS).query((row, n) -> new Counts(row.getLong("accepted"), row.getLong("completed"),
                backlog.pending(), backlog.claimed(), row.getLong("applied"), backlog.halted(), p99)).single();
    }

    /**
     * Posts whose result is not written yet, including ones not sent to TB. Meanwhile the TB balance can run ahead of
     * the PG sum of paid orders. Posts not yet sent are counted too: that only keeps the verdict "settling" a little
     * longer, and never turns it into a false "broken".
     */
    long capturesInFlight() {
        return sql.sql("SELECT count(*) FROM shop_order_command WHERE kind = 'POST' AND completed_at IS NULL")
                .query(Long.class).single();
    }

    long paidOrdersSatang() {
        return sql.sql("SELECT coalesce(sum(amount_satang), 0) FROM shop_order WHERE state = 'PAID'").query(Long.class)
                .single();
    }
}
