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

    @Override
    public long nextSeq(String orderingKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CommandRecord> insertIfAbsent(NewCommand command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CommandRecord> find(String commandId) {
        throw new UnsupportedOperationException();
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
