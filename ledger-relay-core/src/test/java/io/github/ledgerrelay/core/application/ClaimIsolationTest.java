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
 * claim이 후보 하나의 이상을 그 후보에 가두는가(F29). 저장소가 끝난 행을 후보로 잘못 내는 경우는 계약 테스트로 만들 수 없으므로
 * 저장소 장식자로 끼워 넣는다.
 */
@DisplayName("claim 후보 하나가 규칙과 어긋나면 — F29")
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
    @DisplayName("저장소가 끝난 행을 후보로 내면")
    class AFinishedRowIsOffered {

        @Test
        @DisplayName("그 행은 멈추게도 못 하므로 건너뛰되, 이유와 함께 알린다 — 다른 키는 그대로 잡힌다")
        void itIsSkippedAndReportedWithItsCause() {
            // given
            leaky.alsoOffer("pay-9");

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertThat(batch.claimed()).as("잡힌 명령").extracting(ClaimedCommand::commandId).containsExactly("pay-2");
            assertThat(batch.problems()).as("문제가 있던 후보").singleElement().satisfies(problem -> {
                assertThat(problem.commandId()).isEqualTo("pay-9");
                assertThat(problem.halted()).as("멈춤으로 세웠는가").isFalse();
                assertThat(problem.cause()).as("원래 예외").hasMessageContaining("버그");
            });
        }

        @Test
        @DisplayName("워커는 그 후보를 이유와 함께 알린다")
        void theWorkerReportsIt() {
            // given
            leaky.alsoOffer("pay-9");
            RecordingWorkerEvents events = new RecordingWorkerEvents();
            RelayWorker worker = new RelayWorker(relay, new FakeLedger(), new InFlightLimit(1024),
                    new WorkerSettings(128, 1024, LEASE, Duration.ofMillis(200), Duration.ofMillis(100)), events);

            // when
            worker.runOnce();

            // then
            assertThat(events.events()).as("워커 이벤트").contains("malformedCandidate:pay-9");
        }
    }

    private void accepted(String commandId, String orderingKey) {
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer(commandId)
                .orderingKey(orderingKey).debit(1).credit(2).amount(500).ledger(1).code(1).build()));
    }

    /** 진짜 후보 뒤에 지정한 행을 후보로 하나 더 낸다. 저장소 버그를 흉내 낸다. */
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
