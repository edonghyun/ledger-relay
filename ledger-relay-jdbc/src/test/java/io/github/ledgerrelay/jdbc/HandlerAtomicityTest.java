package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("DB에 쓰는 결과 반영 핸들러 — G6")
class HandlerAtomicityTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration BACKOFF = Duration.ofSeconds(5);

    PostgresStorage storage;
    WalletEventHandler handler;
    CommandLifecycle relay;
    ClaimedCommand pay1;

    @BeforeEach
    void givenPay1IsClaimedAndSubmitted() {
        storage = PostgresStorage.withDataSourceTransactions();
        storage.sql().sql("CREATE TABLE IF NOT EXISTS wallet_event (command_id text PRIMARY KEY, state text NOT NULL)")
                .update();
        storage.sql().sql("TRUNCATE wallet_event").update();
        handler = new WalletEventHandler(storage.dataSource());
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(BACKOFF, BACKOFF));
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1").orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build()));
        pay1 = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(pay1);
    }

    @Nested
    @Order(1)
    @DisplayName("핸들러가 끝까지 실행되면")
    class WhenTheHandlerCompletes {

        @Test
        @DisplayName("결과와 앱 테이블의 기록이 함께 남는다")
        void theResultAndTheAppRowCommitTogether() {
            // when
            relay.complete(pay1, LedgerResultCode.Created);

            // then
            assertQueryState("pay-1", State.APPLIED);
            assertWalletEvents("pay-1", "APPLIED");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("핸들러가 앱 테이블에 쓴 뒤 던지면")
    class WhenTheHandlerThrowsAfterWriting {

        @Test
        @DisplayName("결과도 앱 테이블의 기록도 남지 않는다 — 반쯤 남지 않는다")
        void neitherTheResultNorTheAppRowRemains() {
            // given
            handler.failAfterWriteOnce();

            // when
            relay.complete(pay1, LedgerResultCode.Created);

            // then
            assertQueryState("pay-1", State.IN_PROGRESS);
            assertNoWalletEvent("pay-1");
        }

        @Test
        @DisplayName("다시 잡아 같은 결과로 끝내면 적용으로 끝나고 앱 테이블의 기록은 정확히 하나다 — 핸들러 효과가 한 번만 남는다")
        void retryingLeavesExactlyOneAppRow() {
            // given
            handler.failAfterWriteOnce();
            relay.complete(pay1, LedgerResultCode.Created);
            storage.elapse(BACKOFF);
            ClaimedCommand again = relay.claim(10, LEASE).claimed().get(0);

            // when
            relay.complete(again, again.knownResult().orElseThrow());

            // then
            assertQueryState("pay-1", State.APPLIED);
            assertWalletEvents("pay-1", "APPLIED");
        }
    }

    private void assertQueryState(String commandId, State expected) {
        assertThat(relay.status(commandId).orElseThrow().state()).as("%s의 조회 상태", commandId).isEqualTo(expected);
    }

    private void assertWalletEvents(String commandId, String... states) {
        assertThat(walletEvents(commandId)).as("wallet_event에 남은 %s의 기록", commandId).containsExactly(states);
    }

    private void assertNoWalletEvent(String commandId) {
        assertThat(walletEvents(commandId)).as("wallet_event에 %s의 기록이 없기를 기대했다", commandId).isEmpty();
    }

    private List<String> walletEvents(String commandId) {
        return storage.sql().sql("SELECT state FROM wallet_event WHERE command_id = :id")
                .param("id", commandId).query(String.class).list();
    }

    /** 결과를 앱 테이블 wallet_event에 쓰는 핸들러 대역. command_id가 기본 키라 두 번 쓰면 실패한다. */
    private static final class WalletEventHandler implements LedgerOutcomeHandler {

        private final DataSource dataSource;
        private boolean failAfterWrite;

        WalletEventHandler(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void failAfterWriteOnce() {
            failAfterWrite = true;
        }

        @Override
        public void onCompleted(CommandStatus outcome) {
            // Spring이 묶어 둔 트랜잭션 커넥션으로 쓴다: 결과 기록과 한 트랜잭션이다
            JdbcClient.create(dataSource).sql("INSERT INTO wallet_event (command_id, state) VALUES (:id, :state)")
                    .param("id", outcome.commandId()).param("state", outcome.state().name()).update();
            if (failAfterWrite) {
                failAfterWrite = false;
                throw new IllegalStateException("핸들러가 쓴 뒤 일부러 실패한다");
            }
        }
    }
}
