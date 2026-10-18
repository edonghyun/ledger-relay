package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.testing.FakeLedger;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@DisplayName("health — TB 연결과 대기를 알리되, TB가 끊겨도 앱은 UP이다 (03b)")
class LedgerRelayHealthIndicatorTest {

    static final Duration PROBE_TIMEOUT = Duration.ofMillis(200);

    InMemoryStorage storage;
    FakeLedger fake;

    @BeforeEach
    void givenTwoWaitingCommands() {
        storage = InMemoryStorage.create();
        fake = new FakeLedger();
        CommandLifecycle relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                new SequentialTransferIds(), new RecordingHandler(), new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(5)));
        for (String id : new String[] {"pay-1", "pay-2"}) {
            storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer(id).orderingKey("order-" + id)
                    .debit(1).credit(2).amount(500).ledger(1).code(1).build()));
        }
    }

    @Test
    @DisplayName("TB가 답하면 TB UP과 대기 수를 알린다")
    void aReachableLedgerIsUp() {
        // when
        Health health = indicator(storage.transactions()).health();

        // then
        assertThat(health.getStatus()).as("상태").isEqualTo(Status.UP);
        assertThat(health.getDetails()).as("세부").containsEntry("ledger", "UP").containsEntry("unfinished", 2L)
                .containsEntry("halted", 0L);
    }

    @Test
    @DisplayName("TB가 답하지 않으면 TB DOWN으로 알리지만 앱은 UP이다 — 접수는 TB 없이도 된다")
    void aSilentLedgerIsReportedButTheAppStaysUp() {
        // given
        fake.holdLookups();

        // when
        Health health = indicator(storage.transactions()).health();

        // then
        assertThat(health.getStatus()).as("상태").isEqualTo(Status.UP);
        assertThat(health.getDetails()).as("세부").containsEntry("ledger", "DOWN");
    }

    @Test
    @DisplayName("저장소를 읽을 수 없으면 DOWN이다 — PG 없이는 접수도 못 한다")
    void aFailingStoreIsDown() {
        // given
        Transactions broken = new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                throw new IllegalStateException("DB 연결이 끊겼다");
            }

            @Override
            public void requireActive() {
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };

        // when
        Health health = indicator(broken).health();

        // then
        assertThat(health.getStatus()).as("상태").isEqualTo(Status.DOWN);
    }

    private LedgerRelayHealthIndicator indicator(Transactions transactions) {
        return new LedgerRelayHealthIndicator(storage.store(), transactions, fake, PROBE_TIMEOUT);
    }
}
