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

@DisplayName("health — TB 연결과 대기를 알리되, TB가 끊겨도 앱은 UP이다 (03b)")
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
    @DisplayName("TB가 답하지 않아도 health는 기다리지 않는다 — probe timeout(보통 1초)에 걸려 앱이 서비스에서 빠지지 않게")
    void healthDoesNotWaitForASilentLedger() {
        // given
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions());

        // when
        long started = System.nanoTime();
        indicator.health();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // then
        assertThat(took).as("health 한 번에 걸린 시간").isLessThan(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("TB가 답하지 않는 동안 조회는 한 번에 하나만 보낸다 — health 요청마다 TB 클라이언트에 조회가 쌓이지 않게")
    void aSilentLedgerIsProbedOnceAtATime() {
        // given
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions());

        // when
        for (int i = 0; i < 20; i++) {
            indicator.health();
        }

        // then
        assertThat(fake.lookups()).as("TB에 보낸 조회 수").isEqualTo(1);
    }

    @Test
    @DisplayName("health를 5초보다 드물게 불러도 TB가 답하는 동안은 UP이다 — Kubernetes probe 간격(기본 10초)에서도 (F40)")
    void aLedgerThatAnswersIsUpEvenWhenHealthIsCalledRarely() {
        // given: TB가 조금 늦게 답한다. 첫 확인은 답을 받았다
        fake.holdLookups();
        LedgerRelayHealthIndicator indicator = indicator(storage.transactions(), clock);
        indicator.health();
        fake.answerHeldLookups();

        // when: 10초 뒤 다시 부른다. 새 확인은 막 보냈고 아직 답 전이다
        clock.advance(Duration.ofSeconds(10));
        Health health = indicator.health();

        // then
        assertThat(health.getDetails()).as("세부").containsEntry("ledger", "UP");
    }

    @Test
    @DisplayName("보낸 확인이 5초 넘게 답이 없으면 DOWN이다 — 전에 답했더라도")
    void aProbeUnansweredForTooLongIsDown() {
        // given: 첫 확인은 답을 받았고, 다음 확인부터 TB가 답하지 않는다
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
        return new LedgerRelayHealthIndicator(storage.store(), transactions, fake, LEDGER_STALE_AFTER);
    }

    private LedgerRelayHealthIndicator indicator(Transactions transactions, Supplier<Instant> clock) {
        return new LedgerRelayHealthIndicator(storage.store(), transactions, fake, LEDGER_STALE_AFTER, clock);
    }

    /** 테스트가 돌리는 시계. */
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
