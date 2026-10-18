package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.testing.LedgerRelayContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Services on PostgreSQL storage — LedgerRelay contract (DataSource transaction manager)")
class JdbcLedgerRelayTest extends LedgerRelayContract {

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withDataSourceTransactions();
    }

    /** After every test, the head marks and the unfinished counts match the rows. */
    @AfterEach
    void theHeadsStayConsistent() {
        ((PostgresStorage) storage).assertHeadsConsistent();
    }
}
