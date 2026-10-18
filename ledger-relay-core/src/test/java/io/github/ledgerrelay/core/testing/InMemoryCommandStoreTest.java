package io.github.ledgerrelay.core.testing;

import org.junit.jupiter.api.DisplayName;

@DisplayName("In-memory store — CommandStore contract")
class InMemoryCommandStoreTest extends CommandStoreContract {

    @Override
    protected StorageUnderTest createStorage() {
        return InMemoryStorage.create();
    }
}
