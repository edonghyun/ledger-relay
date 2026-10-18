package io.github.ledgerrelay.core.application;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.testing.FakeLedger;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.RecordingWorkerEvents;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Does claim keep a fault in one candidate confined to that candidate (F29)? A store that wrongly offers a finished row
 * cannot be built in a contract test, so a store decorator injects it.
 */
@DisplayName("When one claim candidate breaks the rules — F29")
class ClaimIsolationTest {

    static final Duration LEASE = Duration.ofSeconds(30);

    InMemoryStorage storage;
    LeakyStore leaky;
    CommandLifecycle relay;

    @BeforeEach
    void givenPay9IsFinished() {
        storage = InMemoryStorage.create();
        leaky = new LeakyStore(storage.store());
        relay = new CommandLifecycle(storage.keys(), leaky, storage.transactions(), new SequentialTransferIds(), new RecordingHandler(),
                new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        accepted("pay-9", "order-9");
        relay.complete(relay.claim(10, LEASE).claimed().get(0), Created);
        accepted("pay-2", "order-2");
    }

    @Nested
    @Order(1)
    @DisplayName("When the store offers a finished row as a candidate")
    class AFinishedRowIsOffered {

        @Test
        @DisplayName("That row cannot even be halted, so it is skipped and reported with its cause — other keys are still claimed")
        void itIsSkippedAndReportedWithItsCause() {
            // given
            leaky.alsoOffer("pay-9");

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertThat(batch.claimed()).as("claimed commands").extracting(ClaimedCommand::commandId).containsExactly("pay-2");
            assertThat(batch.problems()).as("candidates with a problem").singleElement().satisfies(problem -> {
                assertThat(problem.commandId()).isEqualTo("pay-9");
                assertThat(problem.halted()).as("whether it was halted").isFalse();
                assertThat(problem.cause()).as("original exception").hasMessageContaining("Bug");
            });
        }

        @Test
        @DisplayName("The worker reports that candidate with its cause")
        void theWorkerReportsIt() {
            // given
            leaky.alsoOffer("pay-9");
            RecordingWorkerEvents events = new RecordingWorkerEvents();
            RelayWorker worker = new RelayWorker(relay, new FakeLedger(), new InFlightLimit(1024),
                    new WorkerSettings(128, 1024, LEASE, Duration.ofMillis(200), Duration.ofMillis(100)), events);

            // when
            worker.runOnce();

            // then
            assertThat(events.events()).as("worker events").contains("malformedCandidate:pay-9");
        }
    }

    private void accepted(String commandId, String orderingKey) {
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer(commandId)
                .orderingKey(orderingKey).debit(1).credit(2).amount(500).ledger(1).code(1).build()));
    }

    /** Offers one more given row as a candidate after the real candidates. Imitates a store bug. */
    private static final class LeakyStore implements CommandStore {

        private final CommandStore real;
        private final List<String> extra = new ArrayList<>();

        LeakyStore(CommandStore real) {
            this.real = real;
        }

        void alsoOffer(String commandId) {
            extra.add(commandId);
        }

        @Override
        public io.github.ledgerrelay.core.spi.Backlog backlog() {
            return real.backlog();
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
            List<DueCommand> due = new ArrayList<>(real.lockDue(limit));
            extra.forEach(id -> due.add(new DueCommand(real.find(id).orElseThrow(), Optional.empty())));
            return due;
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
        public boolean compareAndSet(RowChange change) {
            return real.compareAndSet(change);
        }
    }
}
