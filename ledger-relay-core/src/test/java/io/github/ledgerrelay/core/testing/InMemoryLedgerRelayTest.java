package io.github.ledgerrelay.core.testing;

import org.junit.jupiter.api.DisplayName;

@DisplayName("Service on the in-memory store — LedgerRelay contract")
class InMemoryLedgerRelayTest extends LedgerRelayContract {

    @Override
    protected StorageUnderTest createStorage() {
        return InMemoryStorage.create();
    }
}
