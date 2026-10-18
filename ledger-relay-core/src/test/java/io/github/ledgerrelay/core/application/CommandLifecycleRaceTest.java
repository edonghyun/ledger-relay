package io.github.ledgerrelay.core.application;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * The race in which another worker cuts in after a read and before the write (G5, G6). The in-memory store runs one
 * transaction at a time, so a contract test cannot open this gap. A store decorator injects another worker's re-claim
 * just before the final write.
 */
@DisplayName("When another worker claims the same command again while the result is being written — G5, G6")
class CommandLifecycleRaceTest {

    static final Duration LEASE = Duration.ofSeconds(30);

    InMemoryStorage storage;
    RecordingHandler handler;
    RacingStore racing;
    CommandLifecycle relay;

    @BeforeEach
    void givenPay1IsClaimedAndSubmitted() {
        storage = InMemoryStorage.create();
        handler = new RecordingHandler();
        racing = new RacingStore(storage.store());
        relay = new CommandLifecycle(storage.keys(), racing, storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1").orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build()));
    }

    @Nested
    @Order(1)
    @DisplayName("When another worker raised the token just before the final result is written")
    class TheTokenMovedBeforeTheFinalWrite {

        @Test
        @DisplayName("The write ends with 0 rows and the handler does not run — so a worker that could not record the result does no follow-up work")
        void aWriteThatLosesTheRaceIsFencedAndRunsNoHandler() {
            // given
            ClaimedCommand claimed = relay.claim(10, LEASE).claimed().get(0);
            relay.markSubmitted(claimed);
            racing.reclaimBeforeTheNextFinalWrite();

            // when
            WriteResult result = relay.complete(claimed, Created);

            // then
            assertThat(result).as("write result of the late worker").isEqualTo(new WriteResult.Fenced());
            assertThat(handler.completedFor("pay-1")).as("handler runs for pay-1").isEmpty();
        }
    }

    /**
     * Just before a {@code compareAndSet} to a final state, raises the token by 1 as if another worker had claimed the
     * same row again. It then passes the original write through unchanged, so the original write must fail on the token
     * mismatch.
     */
    private static final class RacingStore implements CommandStore {

        private final CommandStore real;
        private boolean armed;

        RacingStore(CommandStore real) {
            this.real = real;
        }

        void reclaimBeforeTheNextFinalWrite() {
            armed = true;
        }

        @Override
        public boolean compareAndSet(RowChange change) {
            if (armed && change.next().state().isFinal()) {
                armed = false;
                CommandRow now = new CommandRow(StoredState.CLAIMED, change.expectedToken() + 1, 1, Optional.empty(),
                        Optional.empty(), 0);
                if (!real.compareAndSet(new RowChange(change.rowId(), StoredState.CLAIMED, change.expectedToken(), now,
                        new RowChange.Stamp.LeaseFor(LEASE)))) {
                    throw new IllegalStateException("given setup failed: could not inject another worker's re-claim");
                }
            }
            return real.compareAndSet(change);
        }


        @Override
        public Optional<CommandRecord> insertIfAbsent(NewCommand command) {
            return real.insertIfAbsent(command);
        }

        @Override
        public Optional<CommandRecord> find(String commandId) {
            return real.find(commandId);
        }

        @Override
        public io.github.ledgerrelay.core.spi.Backlog backlog() {
            return real.backlog();
        }

        @Override
        public io.github.ledgerrelay.core.spi.WaitingCounts waitingCounts() {
            return real.waitingCounts();
        }

        @Override
        public List<CommandRecord> findFinished(long afterRowId, int limit) {
            return real.findFinished(afterRowId, limit);
        }

        @Override
        public List<CommandRecord> findHalted(long afterRowId, int limit) {
            return real.findHalted(afterRowId, limit);
        }

        @Override
        public List<DueCommand> lockDue(int limit) {
            return real.lockDue(limit);
        }
    }
}
