package io.github.ledgerrelay.core.testing;

import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 저장소 위의 서비스 — LedgerRelay 계약")
class InMemoryLedgerRelayTest extends LedgerRelayContract {

    @Override
    protected StorageUnderTest createStorage() {
        return InMemoryStorage.create();
    }
}
