package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.testing.CommandStoreContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import org.junit.jupiter.api.DisplayName;

@DisplayName("PostgreSQL 저장소 — CommandStore 계약")
class JdbcCommandStoreTest extends CommandStoreContract {

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withDataSourceTransactions();
    }
}
