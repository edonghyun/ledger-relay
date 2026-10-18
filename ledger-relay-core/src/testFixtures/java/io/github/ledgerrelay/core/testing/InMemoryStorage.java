package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Duration;

/** One set of in-memory storage. Used by the contract tests and the stage 6 randomized checks (E6a). */
public record InMemoryStorage(InMemoryCommandStore memory, ManualClock clock) implements StorageUnderTest {

    public static InMemoryStorage create() {
        ManualClock clock = new ManualClock();
        return new InMemoryStorage(new InMemoryCommandStore(clock), clock);
    }

    @Override
    public OrderingKeys keys() {
        return memory;
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
    public void deleteCommand(String commandId) {
        memory.delete(commandId);
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
