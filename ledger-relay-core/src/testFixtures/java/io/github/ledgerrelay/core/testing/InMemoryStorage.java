package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Duration;

/** 메모리 저장소 한 벌. 계약 테스트와 6단계 무작위 검증(E6a)이 쓴다. */
public record InMemoryStorage(InMemoryCommandStore memory, ManualClock clock) implements StorageUnderTest {

    public static InMemoryStorage create() {
        ManualClock clock = new ManualClock();
        return new InMemoryStorage(new InMemoryCommandStore(clock), clock);
    }

    @Override
    public CommandStore store() {
        return memory;
    }

    @Override
    public Transactions transactions() {
        return memory;
    }

    @Override
    public void elapse(Duration duration) {
        clock.advance(duration);
    }

    @Override
    public Class<? extends RuntimeException> outsideTransactionError() {
        return IllegalStateException.class;
    }
}
