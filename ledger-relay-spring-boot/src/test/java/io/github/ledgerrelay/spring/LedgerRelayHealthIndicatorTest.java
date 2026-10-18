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
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@DisplayName("Health — reports the TB connection and the backlog, but the app stays UP when TB is unreachable")
class LedgerRelayHealthIndicatorTest {

    static final Duration LEDGER_STALE_AFTER = Duration.ofSeconds(5);

    InMemoryStorage storage;
    FakeLedger fake;
    final TestClock clock = new TestClock();

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
    @DisplayName("When TB answers, health reports TB UP and the backlog counts")
    void aReachableLedgerIsUp() {
        // when
        Health health = indicator(storage.transactions()).health();

        // then
        assertThat(health.getStatus()).as("status").isEqualTo(Status.UP);
        assertThat(health.getDetails()).as("details").containsEntry("ledger", "UP").containsEntry("unfinished", 2L)
                .containsEntry("halted", 0L);
    }

    @Test
    @DisplayName("When TB does not answer, health reports TB DOWN but the app stays UP — acceptance works without TB")
    void aSilentLedgerIsReportedButTheAppStaysUp() {
        // given
        fake.holdLookups();

        // when
        Health health = indicator(storage.transactions()).health();

        // then
        assertThat(health.getStatus()).as("status").isEqualTo(Status.UP);
        assertThat(health.getDetails()).as("details").containsEntry("ledger", "DOWN");
    }

    @Test
    @DisplayName("Health does not wait when TB does not answer — so the probe timeout (usually 1 second) does not take the app out of service")
    void healthDoesNotWaitForASilentLedger() {
        // given
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions());

        // when
        long started = System.nanoTime();
        indicator.health();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // then
        assertThat(took).as("time taken by one health call").isLessThan(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("While TB does not answer, only one lookup is outstanding at a time — so lookups do not pile up in the TB client with each health request")
    void aSilentLedgerIsProbedOnceAtATime() {
        // given
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions());

        // when
        for (int i = 0; i < 20; i++) {
            indicator.health();
        }

        // then
        assertThat(fake.lookups()).as("lookups sent to TB").isEqualTo(1);
    }

    @Test
    @DisplayName("Health stays UP while TB answers even when called less often than every 5 seconds — also at the Kubernetes probe interval (default 10 seconds) (F40)")
    void aLedgerThatAnswersIsUpEvenWhenHealthIsCalledRarely() {
        // given: TB answers a little late. The first check got its answer
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions(), clock);
        indicator.health();
        fake.answerHeldLookups();

        // when: called again 10 seconds later. The new check was just sent and has no answer yet
        clock.advance(Duration.ofSeconds(10));
        Health health = indicator.health();

        // then
        assertThat(health.getDetails()).as("details").containsEntry("ledger", "UP");
    }

    @Test
    @DisplayName("A check unanswered for more than 5 seconds is DOWN — even if TB answered before")
    void aProbeUnansweredForTooLongIsDown() {
        // given: the first check got its answer; from the next check on, TB does not answer
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions(), clock);
        indicator.health();
        fake.answerHeldLookups();
        clock.advance(Duration.ofSeconds(1));
        indicator.health();

        // when
        clock.advance(Duration.ofSeconds(6));
        Health health = indicator.health();

        // then
        assertThat(health.getDetails()).as("details").containsEntry("ledger", "DOWN");
    }

    @Test
    @DisplayName("When the store cannot be read, health is DOWN — without PG, nothing can be accepted")
    void aFailingStoreIsDown() {
        // given
        Transactions broken = new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                throw new IllegalStateException("DB connection lost");
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
        assertThat(health.getStatus()).as("status").isEqualTo(Status.DOWN);
    }

    private LedgerRelayHealthIndicator indicator(Transactions transactions) {
        return new LedgerRelayHealthIndicator(storage.store(), transactions, fake, LEDGER_STALE_AFTER);
    }

    private LedgerRelayHealthIndicator indicator(Transactions transactions, Supplier<Instant> clock) {
        return new LedgerRelayHealthIndicator(storage.store(), transactions, fake, LEDGER_STALE_AFTER, clock);
    }

    /** A clock the test advances. */
    static final class TestClock implements Supplier<Instant> {
        private Instant now = Instant.parse("2026-10-18T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant get() {
            return now;
        }
    }
}
