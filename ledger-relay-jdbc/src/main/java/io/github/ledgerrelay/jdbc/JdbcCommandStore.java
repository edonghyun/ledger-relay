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
 * PostgreSQL 명령 저장소(스펙 §5.1~§5.6). SQL 네 묶음(넣기, 찾기, 잠그고 읽기, 조건부 쓰기)이며 판단하지 않는다.
 * 순서 키 행은 잡지 않는다. 그 SQL은 {@link JdbcOrderingKeys}에 있다(02e P2e-7).
 * {@link JdbcClient}는 Spring이 묶어 둔 트랜잭션 커넥션을 쓰므로 앱 트랜잭션에 합류한다(G1).
 */
public final class JdbcCommandStore implements CommandStore {

    private final JdbcClient sql;

    public JdbcCommandStore(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * 읽는 컬럼. {@code SELECT c.*}를 쓰지 않는다. 운영 중 {@code ADD COLUMN} 마이그레이션 뒤 서버 준비 문장이
     * "cached plan must not change result type"(0A000)으로 실패하고, 앱 트랜잭션 안이면 그 트랜잭션 전체가 중단된다(F31).
     */
    private static final String COLUMNS = """
            c.id, c.command_id, c.transfer_id, c.ordering_key, c.seq, c.kind, c.reservation_command_id,
            c.pending_transfer_id, c.debit_account_id, c.credit_account_id, c.amount, c.ledger, c.code,
            c.timeout_seconds, c.user_data_64, c.user_data_32, c.content_hash, c.state, c.lease_token,
            c.submit_count, c.known_result, c.result_code, c.handler_failures, c.skip_reason, c.accepted_at,
            c.completed_at""";

    /** 이 행을 지금 트랜잭션이 넣었는가(P2b-2). 아직 쓰기를 하지 않은 트랜잭션은 ID가 없으므로 false다. */
    private static final String ACCEPTED_HERE =
            "coalesce(c.accepted_xact = pg_current_xact_id_if_assigned(), false) AS accepted_in_this_transaction";

    /**
     * 넣는다. 같은 commandId가 이미 있으면 {@code ON CONFLICT DO NOTHING}으로 넘기고 있던 행을 읽는다(P2b-9).
     * 유일성 위반 예외를 잡는 방식은 PostgreSQL 트랜잭션 전체를 중단시켜 앱 트랜잭션을 깨뜨린다(G7).
     * 넣었으면 그 키의 맨 앞을 다시 정한다. 키 행은 잡지 않는다: 접수는 이미 순번을 받으며 키 행을 잡았다(02e P2e-7).
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
        if (!inserted) {
            return find(command.commandId());
        }
        ensureKeyRow(command.orderingKey(), command.seq());
        settleHead(command.orderingKey());
        return Optional.empty();
    }

    /**
     * 순번을 거치지 않고 넣은 행(저장소 계약의 준비)이면 키 행을 만든다. 맨 앞을 넘기는 claim이 이 행을 잡기 때문이다.
     * 이미 있으면 아무것도 하지 않으며, 다른 트랜잭션이 쥔 행이어도 기다리지 않는다({@code DO NOTHING}은 잠그지 않는다).
     */
    private void ensureKeyRow(String orderingKey, long seq) {
        sql.sql("""
                INSERT INTO ledger_ordering_key (ordering_key, next_seq) VALUES (:key, :seq + 1)
                ON CONFLICT (ordering_key) DO NOTHING
                """).param("key", orderingKey).param("seq", seq).update();
    }

    /**
     * 맨 앞을 다시 정한다: 가장 앞 순번의 미완료가 아닌 표시를 끄고, 그 미완료를 켠다. 끄기를 먼저 해야 키당 하나뿐인 유일
     * 인덱스({@code ledger_command_one_head})에 걸리지 않는다. 다른 트랜잭션이 결과를 쓰는 중인 맨 앞은 아직 미완료로
     * 보이므로 건드리지 않고(기다리지 않고), 그 끝난 맨 앞은 다음 claim이 넘긴다.
     */
    private void settleHead(String orderingKey) {
        sql.sql(CLEAR_STALE_HEAD).param("key", orderingKey).update();
        sql.sql(MARK_FIRST_UNFINISHED).param("key", orderingKey).update();
    }

    static final String CLEAR_STALE_HEAD = """
                UPDATE ledger_command SET is_head = false
                WHERE ordering_key = :key AND is_head
                  AND seq IS DISTINCT FROM (SELECT min(seq) FROM ledger_command
                                            WHERE ordering_key = :key AND state IN ('PENDING','CLAIMED','HALTED'))
            """;

    /**
     * 맨 앞이 없을 때만 켠다. READ COMMITTED에서는 끄기 문장 뒤에 같은 키의 완료가 커밋될 수 있다. 그 끝난 맨 앞은 표시를
     * 쥔 채 남으므로, 이 조건이 없으면 맨 앞이 둘이 되어 유일 인덱스가 접수를 깬다. 남은 끝난 맨 앞은 다음 claim이 넘긴다.
     */
    static final String MARK_FIRST_UNFINISHED = """
                UPDATE ledger_command SET is_head = true
                WHERE ordering_key = :key AND NOT is_head
                  AND seq = (SELECT min(seq) FROM ledger_command
                             WHERE ordering_key = :key AND state IN ('PENDING','CLAIMED','HALTED'))
                  AND NOT EXISTS (SELECT 1 FROM ledger_command h WHERE h.ordering_key = :key AND h.is_head)
            """;

    @Override
    public Optional<CommandRecord> find(String commandId) {
        return sql.sql("SELECT " + COLUMNS + ", " + ACCEPTED_HERE + " FROM ledger_command c WHERE c.command_id = :id")
                .param("id", commandId)
                .query((rs, rowNum) -> CommandRows.read(rs))
                .optional();
    }

    /**
     * {@link #lockDue}의 질의. 실행 계획 테스트가 같은 질의를 본다. 맨 앞 표시와 보낼 시각 인덱스({@code ledger_command_ready})로
     * 지금 보낼 수 있는 맨 앞만 시각 순서로 읽는다(02e P2e-1·P2e-3). 마지막 조건은 lease가 끝나는 바로 그 시각의 처리 중
     * 명령을 아직 후보로 보지 않는다({@code ready_at <= now()}만으로는 그 시각이 들어온다).
     */
    static final String LOCK_DUE = """
                SELECT %s, %s,
                       r.command_id AS r_command_id, r.seq AS r_seq, r.state AS r_state
                FROM ledger_command c
                LEFT JOIN ledger_command r ON r.command_id = c.reservation_command_id
                WHERE c.is_head AND c.state IN ('PENDING','CLAIMED') AND c.ready_at <= now()
                  AND (c.state = 'PENDING' OR c.lease_expires_at < now())
                ORDER BY c.ready_at, c.id
                LIMIT :limit
                FOR UPDATE OF c SKIP LOCKED
            """.formatted(COLUMNS, ACCEPTED_HERE);

    /**
     * claim 후보를 잠그고 읽는다(P2b-10). 후보 행만 {@code FOR UPDATE OF c SKIP LOCKED}로 잠근다. 맨 앞 판정은 표시
     * ({@code is_head})로 하므로 다른 워커가 잡은 맨 앞을 건너뛰어도 그 뒤 명령은 후보가 아니다(G4). 참조 예약은 잠그지 않고
     * 읽는다. 예약까지 잠그면 예약을 쓰는 중인 확정이 후보에서 빠진다(F10).
     */
    @Override
    public List<DueCommand> lockDue(int limit) {
        return sql.sql(LOCK_DUE)
                .param("limit", limit)
                .query((rs, rowNum) -> new DueCommand(CommandRows.read(rs), reservation(rs)))
                .list();
    }

    /**
     * 상태와 토큰이 기대와 같을 때만 바꾼다(G5). 시각은 PG {@code now()} 하나로 찍는다(§5.2). 최종 상태로 바꿔도 맨 앞
     * 표시는 그대로 둔다. 다음 claim이 넘긴다(02e P2e-7): 여기서 넘기려면 키 행을 잡아야 하고, 이 트랜잭션은 이어서
     * 핸들러를 부른다.
     */
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
