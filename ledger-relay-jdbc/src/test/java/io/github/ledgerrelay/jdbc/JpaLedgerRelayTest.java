package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.testing.LedgerRelayContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Services on PostgreSQL storage — LedgerRelay contract (JPA transaction manager)")
class JpaLedgerRelayTest extends LedgerRelayContract {

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withJpaTransactions();
    }

    /** After every test, the head marks and the unfinished counts match the rows. */
    @AfterEach
    void theHeadsStayConsistent() {
        ((PostgresStorage) storage).assertHeadsConsistent();
    }

    @Nested
    @Order(20)
    @DisplayName("When a command is submitted in a transaction where the app saves an order with JPA — G1")
    class InsideAJpaTransaction {

        @Test
        @DisplayName("The app entity and the command commit together")
        void anAppEntityAndItsCommandCommitTogether() {
            // when
            storage.transactions().inTransaction(() -> {
                entityManager().persist(new OrderEntity("order-1"));
                return relay.submit(transfer("pay-1", "order-1", 500));
            });

            // then
            assertOrderSaved("order-1");
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("When the app rolls back, the entity and the command disappear together")
        void anAppEntityAndItsCommandRollBackTogether() {
            // when
            catchThrowable(() -> storage.transactions().inTransaction(() -> {
                entityManager().persist(new OrderEntity("order-1"));
                relay.submit(transfer("pay-1", "order-1", 500));
                throw new IllegalStateException("the app rolls back on purpose");
            }));

            // then
            assertNoOrder("order-1");
            assertThat(relay.status("pay-1")).as("rolled-back command").isEmpty();
        }
    }

    private EntityManager entityManager() {
        return ((PostgresStorage) storage).sharedEntityManager();
    }

    private void assertOrderSaved(String orderId) {
        assertThat(orders(orderId)).as("saved order %s", orderId).isEqualTo(1);
    }

    private void assertNoOrder(String orderId) {
        assertThat(orders(orderId)).as("expected no order %s", orderId).isZero();
    }

    private long orders(String orderId) {
        return ((PostgresStorage) storage).sql().sql("SELECT count(*) FROM orders WHERE id = :id")
                .param("id", orderId).query(Long.class).single();
    }
}
