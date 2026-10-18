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

@DisplayName("PostgreSQL 저장소 위의 서비스 — LedgerRelay 계약 (JPA 트랜잭션 매니저)")
class JpaLedgerRelayTest extends LedgerRelayContract {

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withJpaTransactions();
    }

    /** 어느 장이 끝나도 맨 앞 표시와 미완료 개수가 행과 맞다. */
    @AfterEach
    void theHeadsStayConsistent() {
        ((PostgresStorage) storage).assertHeadsConsistent();
    }

    @Nested
    @Order(20)
    @DisplayName("앱이 JPA로 주문을 저장하는 트랜잭션에서 접수하면 — G1")
    class InsideAJpaTransaction {

        @Test
        @DisplayName("앱 엔터티와 명령이 함께 커밋된다")
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
        @DisplayName("앱이 롤백하면 엔터티와 명령이 함께 사라진다")
        void anAppEntityAndItsCommandRollBackTogether() {
            // when
            catchThrowable(() -> storage.transactions().inTransaction(() -> {
                entityManager().persist(new OrderEntity("order-1"));
                relay.submit(transfer("pay-1", "order-1", 500));
                throw new IllegalStateException("앱이 일부러 롤백한다");
            }));

            // then
            assertNoOrder("order-1");
            assertThat(relay.status("pay-1")).as("롤백된 명령").isEmpty();
        }
    }

    private EntityManager entityManager() {
        return ((PostgresStorage) storage).sharedEntityManager();
    }

    private void assertOrderSaved(String orderId) {
        assertThat(orders(orderId)).as("저장된 주문 %s", orderId).isEqualTo(1);
    }

    private void assertNoOrder(String orderId) {
        assertThat(orders(orderId)).as("주문 %s가 없기를 기대했다", orderId).isZero();
    }

    private long orders(String orderId) {
        return ((PostgresStorage) storage).sql().sql("SELECT count(*) FROM orders WHERE id = :id")
                .param("id", orderId).query(Long.class).single();
    }
}
