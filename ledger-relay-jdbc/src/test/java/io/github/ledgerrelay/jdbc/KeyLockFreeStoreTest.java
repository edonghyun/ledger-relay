package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import io.github.ledgerrelay.core.testing.TestTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 명령 저장소({@code CommandStore})는 순서 키 행을 잡지 않는다. 핸들러가 도는 워커 쓰기가 이 저장소만 쓰므로,
 * 저장소가 속에서 키 행을 잡으면 앱의 "주문 행 → 접수" 트랜잭션과 교착한다. 그래서 다른 트랜잭션이 키 행을 쥔 동안에도
 * 저장소의 모든 메서드가 기다리지 않고 끝나야 한다.
 */
@DisplayName("명령 저장소는 순서 키 행을 잡지 않는다 — 키 행을 쥔 트랜잭션이 있어도 기다리지 않는다")
class KeyLockFreeStoreTest {

    static final Duration NOT_BLOCKED = Duration.ofSeconds(2);
    static final Duration LEASE = Duration.ofSeconds(30);

    PostgresStorage storage;
    ClaimedCommand pay1;
    OtherTransaction<Long> keyHolder;

    @BeforeEach
    void givenAnotherTransactionHoldsTheKeyRowOfASubmittedHead() {
        storage = PostgresStorage.withDataSourceTransactions();
        CommandLifecycle relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                new SequentialTransferIds(), new RecordingHandler(),
                new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1").orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build()));
        pay1 = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(pay1);
        keyHolder = OtherTransaction.start(storage.transactions(), () -> storage.sql()
                .sql("SELECT next_seq FROM ledger_ordering_key WHERE ordering_key = 'order-1' FOR UPDATE")
                .query(Long.class).single());
        keyHolder.awaitWork();
    }

    @AfterEach
    void releaseTheKeyRow() {
        keyHolder.close();
    }

    @Test
    @DisplayName("맨 앞의 결과를 써도 기다리지 않는다 — 핸들러가 키 행을 쥔 채 돌지 않게")
    void writingTheHeadsResultDoesNotWait() {
        // when
        boolean written = finishedWithin(() -> storage.store().compareAndSet(appliedPay1()));

        // then
        assertThat(written).as("pay-1의 결과 쓰기").isTrue();
    }

    @Test
    @DisplayName("찾기와 후보 잠그기도 기다리지 않는다")
    void findingAndLockingCandidatesDoNotWait() {
        // when
        Optional<CommandRecord> found = finishedWithin(() -> storage.store().find("pay-1"));
        List<DueCommand> due = finishedWithin(() -> storage.store().lockDue(10));

        // then
        assertThat(found).as("찾은 pay-1").isPresent();
        assertThat(due).as("lease가 남은 맨 앞뿐인 후보").isEmpty();
    }

    @Test
    @DisplayName("같은 키에 행을 넣어도 기다리지 않는다 — 순번을 이미 받은 접수만 넣으므로 키 행은 순번이 잡는다")
    void insertingOnTheKeyDoesNotWait() {
        // when
        Optional<CommandRecord> existing = finishedWithin(() -> storage.store().insertIfAbsent(pay2()));

        // then
        assertThat(existing).as("이미 있던 행").isEmpty();
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private <T> T finishedWithin(java.util.function.Supplier<T> work) {
        try (OtherTransaction<T> other = OtherTransaction.start(storage.transactions(), work)) {
            T result = other.assertFinishedWithin(NOT_BLOCKED);
            other.rollback();
            return result;
        }
    }

    private RowChange appliedPay1() {
        CommandRow applied = new CommandRow(StoredState.APPLIED, pay1.leaseToken(), 1, Optional.of(Created),
                Optional.of(Created), 0);
        return new RowChange(pay1.rowId(), StoredState.CLAIMED, pay1.leaseToken(), applied,
                new RowChange.Stamp.Completion());
    }

    private static NewCommand pay2() {
        return new NewCommand("pay-2", "order-1", 2, Optional.empty(), TestTransferIds.transferIdFor("order-1", 2),
                "hash-of-pay-2", new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                        BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }
}
