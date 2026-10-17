package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.QueuedCommand;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL 저장소(스펙 §5.1~§5.6). SQL 다섯 묶음(순번, 넣기, 찾기, 잠그고 읽기, 조건부 쓰기)이며 판단하지 않는다.
 * {@link JdbcClient}는 Spring이 묶어 둔 트랜잭션 커넥션을 쓰므로 앱 트랜잭션에 합류한다(G1).
 */
public final class JdbcCommandStore implements CommandStore {

    private final JdbcClient sql;

    public JdbcCommandStore(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    /** 이 행을 지금 트랜잭션이 넣었는가(P2b-2). 아직 쓰기를 하지 않은 트랜잭션은 ID가 없으므로 false다. */
    private static final String ACCEPTED_HERE =
            "coalesce(c.accepted_xact = pg_current_xact_id_if_assigned(), false) AS accepted_in_this_transaction";

    /**
     * 키 행을 upsert해 잠그고 다음 순번을 낸다(P2b-8). 잠금은 커밋까지 유지되므로 같은 키의 다음 접수는 이 트랜잭션이
     * 끝날 때까지 기다린다. 그래서 seq 순서가 커밋 순서와 같다(R12).
     */
    @Override
    public long nextSeq(String orderingKey) {
        return sql.sql("""
                INSERT INTO ledger_ordering_key (ordering_key, next_seq) VALUES (:key, 2)
                ON CONFLICT (ordering_key) DO UPDATE SET next_seq = ledger_ordering_key.next_seq + 1
                RETURNING next_seq - 1
                """).param("key", orderingKey).query(Long.class).single();
    }

    /**
     * 넣는다. 같은 commandId가 이미 있으면 {@code ON CONFLICT DO NOTHING}으로 넘기고 있던 행을 읽는다(P2b-9).
     * 유일성 위반 예외를 잡는 방식은 PostgreSQL 트랜잭션 전체를 중단시켜 앱 트랜잭션을 깨뜨린다(G7).
     */
    @Override
    public Optional<CommandRecord> insertIfAbsent(NewCommand command) {
        boolean inserted = sql.sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, reservation_command_id,
                    pending_transfer_id, debit_account_id, credit_account_id, amount, ledger, code, timeout_seconds,
                    user_data_64, user_data_32, content_hash, state)
                VALUES (:command_id, :transfer_id, :ordering_key, :seq, :kind, :reservation_command_id,
                    :pending_transfer_id, :debit_account_id, :credit_account_id, :amount, :ledger, :code,
                    :timeout_seconds, :user_data_64, :user_data_32, :content_hash, 'PENDING')
                ON CONFLICT (command_id) DO NOTHING
                RETURNING id
                """).params(CommandRows.parameters(command)).query(Long.class).optional().isPresent();
        return inserted ? Optional.empty() : find(command.commandId());
    }

    @Override
    public Optional<CommandRecord> find(String commandId) {
        return sql.sql("SELECT c.*, " + ACCEPTED_HERE + " FROM ledger_command c WHERE c.command_id = :id")
                .param("id", commandId)
                .query((rs, rowNum) -> CommandRows.read(rs))
                .optional();
    }

    /**
     * claim 후보를 잠그고 읽는다(P2b-10). 후보 행만 {@code FOR UPDATE OF c SKIP LOCKED}로 잠근다. 맨 앞 판정과 참조 예약은
     * 잠그지 않고 읽는다. 맨 앞 판정까지 잠긴 행을 건너뛰면 다른 워커가 잡은 명령 뒤의 명령이 먼저 나가고(G4), 예약까지
     * 잠그면 예약을 쓰는 중인 확정이 후보에서 빠진다(F10).
     */
    @Override
    public List<DueCommand> lockDue(int limit) {
        return sql.sql("""
                SELECT c.*, %s,
                       r.command_id AS r_command_id, r.seq AS r_seq, r.state AS r_state
                FROM ledger_command c
                LEFT JOIN ledger_command r ON r.command_id = c.reservation_command_id
                WHERE ((c.state = 'PENDING' AND c.next_attempt_at <= now())
                    OR (c.state = 'CLAIMED' AND c.lease_expires_at < now()))
                  AND NOT EXISTS (SELECT 1 FROM ledger_command e
                                  WHERE e.ordering_key = c.ordering_key AND e.seq < c.seq
                                    AND e.state IN ('PENDING','CLAIMED','HALTED'))
                ORDER BY c.id
                LIMIT :limit
                FOR UPDATE OF c SKIP LOCKED
                """.formatted(ACCEPTED_HERE))
                .param("limit", limit)
                .query((rs, rowNum) -> new DueCommand(CommandRows.read(rs), reservation(rs)))
                .list();
    }

    /** 상태와 토큰이 기대와 같을 때만 바꾼다(G5). 시각은 PG {@code now()} 하나로 찍는다(§5.2). */
    @Override
    public boolean compareAndSet(RowChange change) {
        CommandRow next = change.next();
        return sql.sql("""
                UPDATE ledger_command
                SET state = :state, lease_token = :lease_token, submit_count = :submit_count,
                    known_result = :known_result, result_code = :result_code,
                    handler_failures = :handler_failures, skip_reason = :skip_reason%s
                WHERE id = :id AND state = :expected_state AND lease_token = :expected_token
                """.formatted(stampColumns(change.stamp())))
                .param("state", next.state().name())
                .param("lease_token", next.leaseToken())
                .param("submit_count", next.submitCount())
                .param("known_result", CommandRows.wireName(next.knownResult()))
                .param("result_code", CommandRows.wireName(next.resultCode()))
                .param("handler_failures", next.handlerFailures())
                .param("skip_reason", next.skipReason().map(Enum::name).orElse(null))
                .param("seconds", stampSeconds(change.stamp()))
                .param("id", change.rowId())
                .param("expected_state", change.expectedState().name())
                .param("expected_token", change.expectedToken())
                .update() == 1;
    }

    private static Optional<QueuedCommand> reservation(ResultSet rs) throws SQLException {
        String commandId = rs.getString("r_command_id");
        if (commandId == null) {
            return Optional.empty();
        }
        return Optional.of(new QueuedCommand(commandId, rs.getLong("r_seq"), StoredState.valueOf(rs.getString("r_state"))));
    }

    private static String stampColumns(RowChange.Stamp stamp) {
        return switch (stamp) {
            case RowChange.Stamp.LeaseFor lease -> ", lease_expires_at = now() + make_interval(secs => :seconds)";
            case RowChange.Stamp.FirstSubmission submission -> ", first_submitted_at = coalesce(first_submitted_at, now())";
            case RowChange.Stamp.RetryAfter retry -> ", next_attempt_at = now() + make_interval(secs => :seconds)";
            case RowChange.Stamp.Completion completion -> ", completed_at = now()";
            case RowChange.Stamp.Halt halt -> "";
        };
    }

    /** 소수 초. 시각을 바꾸지 않는 표시에는 쓰이지 않는 0을 넘긴다. */
    private static double stampSeconds(RowChange.Stamp stamp) {
        return switch (stamp) {
            case RowChange.Stamp.LeaseFor lease -> lease.lease().toNanos() / 1e9;
            case RowChange.Stamp.RetryAfter retry -> retry.backoff().toNanos() / 1e9;
            case RowChange.Stamp.FirstSubmission submission -> 0;
            case RowChange.Stamp.Completion completion -> 0;
            case RowChange.Stamp.Halt halt -> 0;
        };
    }
}
