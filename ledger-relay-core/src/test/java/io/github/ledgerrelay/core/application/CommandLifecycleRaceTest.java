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
 * 읽은 뒤 쓰기 전에 다른 워커가 끼어드는 경쟁(G5·G6). 메모리 저장소는 트랜잭션을 하나씩 돌리므로 계약 테스트로는 이 틈을
 * 만들 수 없다. 저장소 장식자로 최종 쓰기 직전에 다른 워커의 재claim을 끼워 넣는다.
 */
@DisplayName("결과를 쓰는 사이 다른 워커가 같은 명령을 다시 잡으면 — G5, G6")
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
    @DisplayName("최종 결과를 쓰기 직전에 다른 워커가 토큰을 올렸으면")
    class TheTokenMovedBeforeTheFinalWrite {

        @Test
        @DisplayName("쓰기는 0행으로 끝나고 핸들러는 실행되지 않는다 — 결과를 남기지 못한 워커가 후속 처리를 하지 않게")
        void aWriteThatLosesTheRaceIsFencedAndRunsNoHandler() {
            // given
            ClaimedCommand claimed = relay.claim(10, LEASE).claimed().get(0);
            relay.markSubmitted(claimed);
            racing.reclaimBeforeTheNextFinalWrite();

            // when
            WriteResult result = relay.complete(claimed, Created);

            // then
            assertThat(result).as("늦은 워커의 쓰기 결과").isEqualTo(new WriteResult.Fenced());
            assertThat(handler.completedFor("pay-1")).as("pay-1의 핸들러 실행").isEmpty();
        }
    }

    /**
     * 최종 상태로 가는 {@code compareAndSet} 직전에, 같은 행을 다른 워커가 다시 잡은 것처럼 토큰을 1 올려 둔다. 그 뒤 원래
     * 쓰기를 그대로 전하므로 원래 쓰기는 토큰이 맞지 않아 실패해야 한다.
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
                    throw new IllegalStateException("given 준비 실패: 다른 워커의 재claim을 끼워 넣지 못했다");
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
        public List<CommandRecord> findHalted(long afterRowId, int limit) {
            return real.findHalted(afterRowId, limit);
        }

        @Override
        public List<DueCommand> lockDue(int limit) {
            return real.lockDue(limit);
        }
    }
}
