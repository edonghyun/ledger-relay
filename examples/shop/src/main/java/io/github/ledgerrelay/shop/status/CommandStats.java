package io.github.ledgerrelay.shop.status;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 화면의 타일과 단계 칸 숫자. 라이브러리의 명령 표를 직접 센다. 데모 크기에서는 질의 하나로 충분하다. 운영 지표는 나중에
 * Micrometer로 바꿀 자리다(docs/development.md).
 */
@Component
class CommandStats {

    record Counts(long accepted, long completed, long pending, long claimed, long applied, long halted, Long p99Millis) {

        long backlog() {
            return pending + claimed + halted;
        }
    }

    private static final String COUNTS = "SELECT count(*) AS accepted, "
            + "count(*) FILTER (WHERE state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED')) AS completed, "
            + "count(*) FILTER (WHERE state = 'PENDING') AS pending, "
            + "count(*) FILTER (WHERE state = 'CLAIMED') AS claimed, "
            + "count(*) FILTER (WHERE state = 'APPLIED') AS applied, "
            + "count(*) FILTER (WHERE state = 'HALTED') AS halted FROM ledger_command";
    /** 최근에 끝난 1,000건의 접수→완료 시간 p99(밀리초). */
    private static final String P99 = "SELECT round(1000 * percentile_cont(0.99) WITHIN GROUP (ORDER BY took)) FROM ("
            + "SELECT extract(epoch FROM completed_at - accepted_at) AS took FROM ledger_command "
            + "WHERE completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT 1000) recent";

    private final JdbcClient sql;

    CommandStats(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    Counts counts() {
        Long p99 = sql.sql(P99).query(Long.class).optional().orElse(null);
        return sql.sql(COUNTS).query((row, n) -> new Counts(row.getLong("accepted"), row.getLong("completed"),
                row.getLong("pending"), row.getLong("claimed"), row.getLong("applied"), row.getLong("halted"), p99)).single();
    }

    /** TB에 보냈지만 결과를 아직 쓰지 못한 확정. 그 사이 TB 잔액이 PG의 결제 완료 합보다 앞설 수 있다. */
    long capturesInFlight() {
        return sql.sql("SELECT count(*) FROM ledger_command WHERE kind = 'POST' AND state = 'CLAIMED'")
                .query(Long.class).single();
    }

    long paidOrdersSatang() {
        return sql.sql("SELECT coalesce(sum(amount_satang), 0) FROM shop_order WHERE state = 'PAID'").query(Long.class)
                .single();
    }
}
