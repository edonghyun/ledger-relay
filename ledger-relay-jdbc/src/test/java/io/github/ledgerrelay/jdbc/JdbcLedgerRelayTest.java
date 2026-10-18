package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.testing.LedgerRelayContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("PostgreSQL 저장소 위의 서비스 — LedgerRelay 계약 (DataSource 트랜잭션 매니저)")
class JdbcLedgerRelayTest extends LedgerRelayContract {

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withDataSourceTransactions();
    }

    /** 어느 장이 끝나도 맨 앞 표시와 미완료 개수가 행과 맞다. */
    @AfterEach
    void theHeadsStayConsistent() {
        ((PostgresStorage) storage).assertHeadsConsistent();
    }
}
