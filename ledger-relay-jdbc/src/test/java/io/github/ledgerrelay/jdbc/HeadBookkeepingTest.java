package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import io.github.ledgerrelay.core.testing.TestTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 키마다 맨 앞 미완료 명령의 표시(is_head)와 미완료 개수(unfinished_count)를 쓰는 시점에 고친다(02e P2e-4). claim 질의가
 * 막힌 키의 백로그를 훑지 않고 맨 앞만 읽으려면 이 표시가 행과 늘 맞아야 한다.
 */
@DisplayName("키마다 맨 앞 표시와 미완료 개수 — 02e P2e-4·P2e-5")
class HeadBookkeepingTest {

    static final Duration LEASE = Duration.ofSeconds(30);
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
    @DisplayName("접수하면")
    class Submitting {

        @Test
        @DisplayName("키의 첫 명령이 맨 앞이다")
        void theFirstCommandOfAKeyIsItsHead() {
            // when
            submit(transfer("pay-1"));

            // then
            assertHeadOf("order-1", "pay-1");
            assertUnfinishedCount("order-1", 1);
        }

        @Test
        @DisplayName("뒤에 온 명령은 맨 앞 뒤에서 기다린다")
        void aLaterCommandWaitsBehindTheHead() {
            // when
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));

            // then
            assertHeadOf("order-1", "pay-1");
            assertUnfinishedCount("order-1", 2);
        }

        @Test
        @DisplayName("롤백된 접수는 아무것도 남기지 않는다")
        void aRolledBackSubmitLeavesNothing() {
            // when
            assertThatThrownBy(() -> storage.transactions().inTransaction(() -> {
                relay.submit(transfer("pay-1"));
                throw new IllegalStateException("앱이 롤백한다");
            })).isInstanceOf(IllegalStateException.class);

            // then
            assertNoHeadOf("order-1");
            assertUnfinishedCount("order-1", 0);
        }

        @Test
        @DisplayName("같은 명령을 다시 접수해도 한 번만 센다")
        void aDuplicateSubmitDoesNotCount() {
            // when
            submit(transfer("pay-1"));
            submit(transfer("pay-1"));

            // then
            assertHeadOf("order-1", "pay-1");
            assertUnfinishedCount("order-1", 1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("맨 앞이 끝나면")
    class Finishing {

        @Test
        @DisplayName("맨 앞이 다음 명령으로 넘어간다")
        void finishingTheHeadPassesItOn() {
            // given
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));

            // when
            completeTheHead();

            // then
            assertHeadOf("order-1", "pay-2");
            assertUnfinishedCount("order-1", 1);
        }

        @Test
        @DisplayName("마지막 명령이 끝나면 맨 앞이 없다")
        void finishingTheLastLeavesNoHead() {
            // given
            submit(transfer("pay-1"));

            // when
            completeTheHead();

            // then
            assertNoHeadOf("order-1");
            assertUnfinishedCount("order-1", 0);
        }

        @Test
        @DisplayName("멈춘 맨 앞은 계속 키를 막고, 운영자가 포기하면 다음 명령으로 넘어간다 — F9")
        void aHaltedHeadStillBlocksAndAbandoningPassesItOn() {
            // given: capture-1이 같은 키의 뒤에 있는 예약을 참조해 claim에서 멈춘다
            insertPastTheChecks(reserveRow("hold-1", 2));
            insertPastTheChecks(postRow("capture-1", 1, "hold-1"));
            assertThat(relay.claim(10, LEASE).halted()).as("멈춘 명령").containsExactly("capture-1");
            assertHeadOf("order-1", "capture-1");

            // when
            relay.abandonHalted("capture-1");

            // then
            assertHeadOf("order-1", "hold-1");
            assertUnfinishedCount("order-1", 1);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("DB를 직접 고친 뒤")
    class AfterHandEdits {

        @Test
        @DisplayName("다시 계산하면 행에서 맨 앞과 개수를 되찾는다 — P2e-5")
        void rebuildRepairsHandEditedRows() {
            // given
            submit(transfer("pay-1"));
            submit(transfer("pay-2"));
            storage.sql().sql("UPDATE ledger_command SET is_head = false").update();
            storage.sql().sql("UPDATE ledger_ordering_key SET unfinished_count = 7").update();

            // when
            storage.rebuildHeads();

            // then
            assertHeadOf("order-1", "pay-1");
            assertUnfinishedCount("order-1", 2);
        }

        @Test
        @DisplayName("다시 계산하는 동안 들어온 접수도 센다 — 옛 스냅숏의 개수로 덮어쓰지 않는다")
        void rebuildWaitsForAnOpenSubmit() {
            // given: pay-2 접수가 열려 있다
            submit(transfer("pay-1"));
            try (OtherTransaction<CommandReceipt> open = OtherTransaction.start(storage.transactions(),
                    () -> relay.submit(transfer("pay-2")));
                 OtherTransaction<Boolean> rebuild = startAfter(open, () -> {
                     storage.rebuildHeads();
                     return true;
                 })) {
                rebuild.assertStillWorkingAfter(BLOCKED);

                // when
                open.commit();
                rebuild.awaitWork();
                rebuild.commit();
            }

            // then
            assertHeadOf("order-1", "pay-1");
            assertUnfinishedCount("order-1", 2);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, java.util.function.Supplier<T> work) {
        earlier.awaitWork();
        return OtherTransaction.start(storage.transactions(), work);
    }

    private void submit(LedgerCommand command) {
        storage.transactions().inTransaction(() -> relay.submit(command));
    }

    private void completeTheHead() {
        ClaimedCommand claimed = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(claimed);
        relay.complete(claimed, Created);
    }

    private void insertPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + row.commandId() + "가 이미 있었다");
        }
    }

    private void assertHeadOf(String orderingKey, String commandId) {
        assertThat(heads(orderingKey)).as("%s의 맨 앞", orderingKey).containsExactly(commandId);
    }

    private void assertNoHeadOf(String orderingKey) {
        assertThat(heads(orderingKey)).as("%s의 맨 앞", orderingKey).isEmpty();
    }

    private java.util.List<String> heads(String orderingKey) {
        return storage.sql().sql("SELECT command_id FROM ledger_command WHERE ordering_key = :key AND is_head")
                .param("key", orderingKey).query(String.class).list();
    }

    /** 키 행이 없으면 0으로 본다(롤백된 첫 접수는 키 행도 남기지 않는다). */
    private void assertUnfinishedCount(String orderingKey, long expected) {
        long count = storage.sql().sql("SELECT unfinished_count FROM ledger_ordering_key WHERE ordering_key = :key")
                .param("key", orderingKey).query(Long.class).optional().orElse(0L);
        assertThat(count).as("%s의 미완료 개수", orderingKey).isEqualTo(expected);
    }

    private static LedgerCommand transfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }

    private static NewCommand reserveRow(String commandId, long seq) {
        return new NewCommand(commandId, "order-1", seq, Optional.empty(), TestTransferIds.transferIdFor("order-1", seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    private static NewCommand postRow(String commandId, long seq, String reservationCommandId) {
        return new NewCommand(commandId, "order-1", seq, Optional.of(reservationCommandId),
                TestTransferIds.transferIdFor("order-1", seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(TestTransferIds.transferIdFor("order-1", 2)), 0, 0));
    }
}
