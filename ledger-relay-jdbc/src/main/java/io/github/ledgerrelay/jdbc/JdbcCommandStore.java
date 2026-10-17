package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
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

    @Override
    public List<DueCommand> lockDue(int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean compareAndSet(RowChange change) {
        throw new UnsupportedOperationException();
    }
}
