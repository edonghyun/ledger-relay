package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * READ COMMITTED(기본) 앱 트랜잭션 둘이 같은 commandId를 겹쳐 접수할 때(G7). 늦은 쪽은 먼저 커밋한 쪽을 기다린 뒤 같은 내용이면
 * 같은 접수증을, 다른 내용이면 내용 충돌을 받는다. 다른 순서 키면 순번 단계에서 기다리지 않으므로 넣기({@code ON CONFLICT})에서
 * 만난다.
 */
@DisplayName("READ COMMITTED 앱 트랜잭션에서 같은 commandId의 동시 접수 — G7")
class ReadCommittedSubmitTest {

    static final Duration BLOCKED = Duration.ofMillis(300);

    PostgresStorage storage;
    CommandLifecycle relay;

    @BeforeEach
    void givenAnEmptyRelay() {
        storage = PostgresStorage.withDataSourceTransactions();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
    }

    @Nested
    @Order(1)
    @DisplayName("같은 순서 키로 겹쳐 접수하면")
    class OnTheSameKey {

        @Test
        @DisplayName("같은 내용이면 늦은 쪽도 먼저 커밋한 쪽의 접수증을 받고, 행은 하나다")
        void theSameContentGetsTheFirstReceipt() {
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-1", "order-1", 500)));
                 OtherTransaction<CommandReceipt> second = startAfter(first,
                         () -> relay.submit(transfer("pay-1", "order-1", 500)))) {
                second.assertStillWorkingAfter(BLOCKED);

                // when
                first.commit();

                // then
                assertThat(second.awaitWork()).as("늦은 접수의 접수증").isEqualTo(first.awaitWork());
                second.commit();
                assertOneRowOf("pay-1");
            }
        }

        @Test
        @DisplayName("다른 내용이면 늦은 쪽은 내용 충돌로 거부된다")
        void otherContentIsAConflict() {
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-1", "order-1", 500)));
                 OtherTransaction<CommandReceipt> second = startAfter(first,
                         () -> relay.submit(transfer("pay-1", "order-1", 501)))) {
                second.assertStillWorkingAfter(BLOCKED);

                // when
                first.commit();

                // then
                assertThatThrownBy(second::awaitWork).as("늦은 접수").isInstanceOf(CommandConflictException.class);
                assertOneRowOf("pay-1");
            }
        }
    }

    @Nested
    @Order(2)
    @DisplayName("다른 순서 키로 겹쳐 접수하면 — 넣기에서 만난다")
    class OnAnotherKey {

        @Test
        @DisplayName("다른 내용이면 늦은 쪽은 내용 충돌로 거부되고, 행은 하나다")
        void otherContentIsAConflict() {
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-1", "order-1", 500)));
                 OtherTransaction<CommandReceipt> second = startAfter(first,
                         () -> relay.submit(transfer("pay-1", "order-2", 500)))) {
                second.assertStillWorkingAfter(BLOCKED);

                // when
                first.commit();

                // then
                assertThatThrownBy(second::awaitWork).as("늦은 접수").isInstanceOf(CommandConflictException.class);
                assertOneRowOf("pay-1");
            }
        }
    }

    private static LedgerCommand transfer(String commandId, String orderingKey, long amount) {
        return LedgerCommand.transfer(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(amount).ledger(1).code(1).build();
    }

    private <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, java.util.function.Supplier<T> work) {
        earlier.awaitWork();
        return OtherTransaction.start(storage.transactions(), work);
    }

    private void assertOneRowOf(String commandId) {
        assertThat(storage.sql().sql("SELECT count(*) FROM ledger_command WHERE command_id = :id")
                .param("id", commandId).query(Long.class).single()).as("%s의 행 수", commandId).isEqualTo(1L);
    }
}
