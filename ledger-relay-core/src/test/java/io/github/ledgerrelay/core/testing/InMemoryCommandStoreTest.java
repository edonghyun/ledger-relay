package io.github.ledgerrelay.core.testing;

import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 저장소 — CommandStore 계약")
class InMemoryCommandStoreTest extends CommandStoreContract {

    @Override
    protected StorageUnderTest createStorage() {
        return InMemoryStorage.create();
    }
}
