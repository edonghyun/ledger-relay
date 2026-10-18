package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.QueuedCommand;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.Backlog;
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
 * PostgreSQL command store. Four groups of SQL (insert, find, lock and read, conditional write); it makes no decisions.
 * It does not lock ordering key rows. That SQL is in {@link JdbcOrderingKeys}.
 * {@link JdbcClient} uses the transaction connection that Spring has bound, so it joins the app transaction (G1).
 */
public final class JdbcCommandStore implements CommandStore {

    private final JdbcClient sql;

    public JdbcCommandStore(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * Columns to read. {@code SELECT c.*} is not used: after an {@code ADD COLUMN} migration in production, server-side
     * prepared statements fail with "cached plan must not change result type" (0A000), and inside an app transaction
     * the whole transaction is aborted (F31).
     */
    private static final String COLUMNS = """
            c.id, c.command_id, c.transfer_id, c.ordering_key, c.seq, c.kind, c.reservation_command_id,
            c.pending_transfer_id, c.debit_account_id, c.credit_account_id, c.amount, c.ledger, c.code,
            c.timeout_seconds, c.user_data_64, c.user_data_32, c.content_hash, c.state, c.lease_token,
            c.submit_count, c.known_result, c.result_code, c.handler_failures, c.skip_reason, c.accepted_at,
            c.completed_at, c.halt_reason, c.halted_at""";

    /** Whether the current transaction inserted this row. A transaction that has not written yet has no ID: false. */
    private static final String ACCEPTED_HERE =
            "coalesce(c.accepted_xact = pg_current_xact_id_if_assigned(), false) AS accepted_in_this_transaction";

    /**
     * Inserts. If the same commandId already exists, {@code ON CONFLICT DO NOTHING} skips the insert and the existing
     * row is read. Catching a unique violation exception instead would abort the whole PostgreSQL transaction and break
     * the app transaction (G7). After an insert it settles the head of the key again. It does not lock the key row:
     * acceptance already locked it when it took the sequence number.
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
     * Creates the key row for a row inserted without taking a sequence number (setup in store contract tests), because
     * the claim that passes on the head locks this row. If it exists, nothing happens, and it does not wait even if
     * another transaction holds the row ({@code DO NOTHING} does not lock).
     */
    private void ensureKeyRow(String orderingKey, long seq) {
        sql.sql("""
                INSERT INTO ledger_ordering_key (ordering_key, next_seq) VALUES (:key, :seq + 1)
                ON CONFLICT (ordering_key) DO NOTHING
                """).param("key", orderingKey).param("seq", seq).update();
    }

    /**
     * Settles the head again: clears the mark that is not on the unfinished command with the lowest sequence number,
     * then sets it on that command. Clearing first keeps the one-per-key unique index
     * ({@code ledger_command_one_head}) from rejecting the update. A head whose result another transaction is writing
     * still looks unfinished, so it is left alone (without waiting), and the next claim passes on that finished head.
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
     * Sets the mark only when the key has no head. Under READ COMMITTED a completion on the same key can commit after
     * the clearing statement. That finished head keeps its mark, so without this condition the key would have two
     * heads and the unique index would break acceptance. The next claim passes on the remaining finished head.
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

    /** Counts the backlog (health). Reads only rows of the unfinished index ({@code ledger_command_unfinished}). */
    /** Health counts the backlog up to this cap and reports "this many or more" above it. Metrics count exactly. */
    static final int BACKLOG_COUNT_CAP = 1_000;

    /**
     * The three queries health uses to count the backlog. They bound the amount read, so health does not slow down as
     * the backlog grows during an outage: counts stop at the cap, and the oldest waiting command is read from the first
     * entry of {@code ledger_command_waiting} (the id index of pending and in-flight commands). A query plan test
     * checks them.
     */
    static final List<String> BACKLOG = List.of(
            "SELECT count(*) FROM (SELECT 1 FROM ledger_command WHERE state IN ('PENDING','CLAIMED') ORDER BY id"
                    + " LIMIT :cap) w",
            "SELECT count(*) FROM (SELECT 1 FROM ledger_command WHERE state = 'HALTED' LIMIT :cap) h",
            "SELECT accepted_at FROM ledger_command WHERE state IN ('PENDING','CLAIMED') ORDER BY id LIMIT 1");

    @Override
    public Backlog backlog() {
        long unfinished = sql.sql(BACKLOG.get(0)).param("cap", BACKLOG_COUNT_CAP).query(Long.class).single();
        long halted = sql.sql(BACKLOG.get(1)).param("cap", BACKLOG_COUNT_CAP).query(Long.class).single();
        Optional<java.time.Instant> oldest = sql.sql(BACKLOG.get(2)).query(java.time.OffsetDateTime.class).optional()
                .map(java.time.OffsetDateTime::toInstant);
        return new Backlog(unfinished, halted, oldest,
                unfinished >= BACKLOG_COUNT_CAP || halted >= BACKLOG_COUNT_CAP);
    }

    /** Reads finished commands in row order (reconciliation). Reads in primary key order and skips non-final rows. */
    @Override
    public List<CommandRecord> findFinished(long afterRowId, int limit) {
        return sql.sql("SELECT " + COLUMNS + ", " + ACCEPTED_HERE + " FROM ledger_command c"
                        + " WHERE c.state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED') AND c.id > :after"
                        + " ORDER BY c.id LIMIT :limit")
                .param("after", afterRowId).param("limit", limit)
                .query((rs, rowNum) -> CommandRows.read(rs)).list();
    }

    /** Reads halted commands in row order. Reads only the {@code ledger_command_halted} index. */
    @Override
    public List<CommandRecord> findHalted(long afterRowId, int limit) {
        return sql.sql("SELECT " + COLUMNS + ", " + ACCEPTED_HERE
                + " FROM ledger_command c WHERE c.state = 'HALTED' AND c.id > :after ORDER BY c.id LIMIT :limit")
                .param("after", afterRowId).param("limit", limit)
                .query((rs, rowNum) -> CommandRows.read(rs)).list();
    }

    /**
     * The query of {@link #lockDue}. A query plan test checks the same query. Using the head mark and the send-time
     * index ({@code ledger_command_ready}), it reads only heads that can be sent now, in time order. The last condition
     * keeps an in-flight command out of the candidates at the exact instant its lease ends
     * ({@code ready_at <= now()} alone would include that instant).
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
     * Locks and reads claim candidates. Only candidate rows are locked, with {@code FOR UPDATE OF c SKIP LOCKED}. The
     * head is decided by the mark ({@code is_head}), so when a head locked by another worker is skipped, the command
     * behind it is still not a candidate (G4). The referenced reservation is read without a lock. Locking the
     * reservation too would drop a post that is writing the reservation from the candidates (F10).
     */
    @Override
    public List<DueCommand> lockDue(int limit) {
        return sql.sql(LOCK_DUE)
                .param("limit", limit)
                .query((rs, rowNum) -> new DueCommand(CommandRows.read(rs), reservation(rs)))
                .list();
    }

    /**
     * Changes the row only when state and token match the expected values (G5). Times come from the single PG
     * {@code now()}. Moving to a final state keeps the head mark. The next claim passes it on: passing it on here would
     * need the key row lock, and this transaction goes on to call the handler.
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
                .param("halt_reason", change.stamp() instanceof RowChange.Stamp.Halt halt ? halt.reason().name() : null)
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
            case RowChange.Stamp.Completion completion -> ", completed_at = now(), halt_reason = NULL, halted_at = NULL";
            case RowChange.Stamp.Halt halt -> ", halt_reason = :halt_reason, halted_at = now()";
        };
    }

    /** Fractional seconds. Stamps that do not change a time get an unused 0. */
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
