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
 * When two READ COMMITTED (default) app transactions submit the same commandId at the same time (G7). The later one
 * waits for the one that commits first, then gets the same receipt if the content is the same, or a content conflict
 * if it differs. On different ordering keys they do not wait at the sequence step, so they meet at the insert
 * ({@code ON CONFLICT}).
 */
@DisplayName("Concurrent submits of the same commandId in READ COMMITTED app transactions — G7")
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
    @DisplayName("When both submit on the same ordering key")
    class OnTheSameKey {

        @Test
        @DisplayName("With the same content, the later one also gets the receipt of the one that committed first, and there is one row")
        void theSameContentGetsTheFirstReceipt() {
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-1", "order-1", 500)));
                 OtherTransaction<CommandReceipt> second = startAfter(first,
                         () -> relay.submit(transfer("pay-1", "order-1", 500)))) {
                second.assertStillWorkingAfter(BLOCKED);

                // when
                first.commit();

                // then
                assertThat(second.awaitWork()).as("receipt of the later submit").isEqualTo(first.awaitWork());
                second.commit();
                assertOneRowOf("pay-1");
            }
        }

        @Test
        @DisplayName("With different content, the later one is rejected with a content conflict")
        void otherContentIsAConflict() {
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-1", "order-1", 500)));
                 OtherTransaction<CommandReceipt> second = startAfter(first,
                         () -> relay.submit(transfer("pay-1", "order-1", 501)))) {
                second.assertStillWorkingAfter(BLOCKED);

                // when
                first.commit();

                // then
                assertThatThrownBy(second::awaitWork).as("later submit").isInstanceOf(CommandConflictException.class);
                assertOneRowOf("pay-1");
            }
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When they submit on different ordering keys — they meet at the insert")
    class OnAnotherKey {

        @Test
        @DisplayName("With different content, the later one is rejected with a content conflict, and there is one row")
        void otherContentIsAConflict() {
            try (OtherTransaction<CommandReceipt> first = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-1", "order-1", 500)));
                 OtherTransaction<CommandReceipt> second = startAfter(first,
                         () -> relay.submit(transfer("pay-1", "order-2", 500)))) {
                second.assertStillWorkingAfter(BLOCKED);

                // when
                first.commit();

                // then
                assertThatThrownBy(second::awaitWork).as("later submit").isInstanceOf(CommandConflictException.class);
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
                .param("id", commandId).query(Long.class).single()).as("row count of %s", commandId).isEqualTo(1L);
    }
}
