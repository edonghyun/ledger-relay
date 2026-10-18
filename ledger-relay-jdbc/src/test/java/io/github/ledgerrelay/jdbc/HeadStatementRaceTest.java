package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 맨 앞을 정하는 SQL 문장 사이에 다른 트랜잭션이 커밋할 때. READ COMMITTED는 문장마다 새 스냅숏을 잡으므로,
 * 트랜잭션 경계만 엇갈리는 {@code HeadRaceTest}로는 보이지 않는 경쟁이다. 문장을 하나씩 실행해 그 사이에 상대를 커밋시킨다.
 */
@DisplayName("맨 앞을 정하는 문장 사이에 다른 트랜잭션이 커밋하면")
class HeadStatementRaceTest {

    static final Duration BLOCKED = Duration.ofMillis(300);
    static final Duration NOT_BLOCKED = Duration.ofSeconds(2);
    static final Duration LEASE = Duration.ofSeconds(30);
    static final long GATE = 4242;

    PostgresStorage storage;
    CommandLifecycle relay;
    ClaimedCommand pay1;

    @BeforeEach
    void givenPay1IsSubmittedToTheLedger() {
        storage = PostgresStorage.withDataSourceTransactions();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        storage.transactions().inTransaction(() -> relay.submit(transfer("pay-1")));
        pay1 = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(pay1);
    }

    @AfterEach
    void theHeadsStayConsistent() {
        storage.assertHeadsConsistent();
    }

    @Nested
    @Order(1)
    @DisplayName("접수가 맨 앞을 정하는 동안")
    class WhileASubmitSettlesTheHead {

        @Test
        @DisplayName("끄기와 켜기 사이에 맨 앞의 완료가 커밋돼도 접수는 실패하지 않고, 다음 claim이 뒤 명령을 잡는다")
        void aCompletionCommittingBetweenTheTwoStatementsDoesNotFailTheSubmit() {
            CountDownLatch cleared = new CountDownLatch(1);
            CountDownLatch markNow = new CountDownLatch(1);
            try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(), this::complete)) {
                completion.awaitWork();
                try (OtherTransaction<Boolean> submit = OtherTransaction.start(storage.transactions(), () -> {
                    long seq = storage.keys().lockKeyAndTakeSeq("order-1");
                    insertPendingRow("pay-2", seq);
                    storage.sql().sql(JdbcCommandStore.CLEAR_STALE_HEAD).param("key", "order-1").update();
                    cleared.countDown();
                    await(markNow);
                    storage.sql().sql(JdbcCommandStore.MARK_FIRST_UNFINISHED).param("key", "order-1").update();
                    return true;
                })) {
                    // given: 접수가 끄기 문장을 마쳤고, 그 뒤 pay-1의 완료가 커밋된다
                    await(cleared);
                    completion.commit();

                    // when
                    markNow.countDown();
                    submit.awaitWork();
                    submit.commit();
                }
            }

            // then
            assertThat(claimedNow()).as("다음 claim이 잡은 명령").containsExactly("pay-2");
        }

        private boolean complete() {
            return HeadStatementRaceTest.this.complete(pay1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("claim이 끝난 맨 앞을 넘기는 동안")
    class WhileAClaimPassesOn {

        @Test
        @DisplayName("다른 워커가 먼저 넘긴 키를 늦게 도착한 넘기기가 잡아도, 결과를 쓰는 중인 새 맨 앞을 기다리지 않는다 — 핸들러와 교착하지 않게")
        void aLatePassOnDoesNotWaitForTheNewHeadBeingCompleted() {
            // given: pay-1이 끝났고 pay-2가 기다린다. 늦은 워커의 넘기기가 스냅숏을 잡은 채 멈춰 있다
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-2")));
            complete(pay1);
            try (OtherTransaction<Boolean> gate = OtherTransaction.start(storage.transactions(), () -> {
                storage.sql().sql("SELECT pg_advisory_xact_lock(:gate)").param("gate", GATE).query().singleRow();
                return true;
            })) {
                gate.awaitWork();
                try (OtherTransaction<List<String>> late = OtherTransaction.start(storage.transactions(),
                        HeadStatementRaceTest.this::latePassOn)) {
                    late.assertStillWorkingAfter(BLOCKED);
                    // 그사이 다른 워커가 order-1을 넘겨 pay-2를 잡고, pay-2의 결과를 쓰는 트랜잭션이 열려 있다
                    ClaimedCommand pay2 = relay.claim(10, LEASE).claimed().get(0);
                    relay.markSubmitted(pay2);
                    try (OtherTransaction<Boolean> completion = OtherTransaction.start(storage.transactions(),
                            () -> complete(pay2))) {
                        completion.awaitWork();

                        // when
                        gate.commit();

                        // then
                        assertThat(late.assertFinishedWithin(NOT_BLOCKED)).as("늦은 넘기기가 잡은 키")
                                .containsExactly("order-1");
                        late.commit();
                        completion.commit();
                    }
                }
            }
        }

        @Test
        @DisplayName("켜기 직전에 새 맨 앞의 완료가 커밋돼도 맨 앞을 하나 더 켜지 않는다 — claim이 유일 인덱스에 깨지지 않게")
        void markingDoesNotAddASecondHeadNextToAFinishedOne() {
            // given: 맨 앞이 pay-2로 넘어갔고 pay-3이 기다린다. pay-2가 방금 끝났다(표시는 쥔 채)
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-2")));
            storage.transactions().inTransaction(() -> relay.submit(transfer("pay-3")));
            complete(pay1);
            ClaimedCommand pay2 = relay.claim(10, LEASE).claimed().get(0);
            relay.markSubmitted(pay2);
            complete(pay2);

            // when: 늦은 넘기기가 pay-1의 끄기만 마친 상태에서 켜기를 실행한다
            int marked = storage.transactions().inTransaction(() -> storage.sql().sql(JdbcOrderingKeys.MARK_NEXT_HEADS)
                    .param("keys", List.of("order-1")).update());

            // then
            assertThat(marked).as("맨 앞으로 켠 행").isZero();
            assertThat(claimedNow()).as("다음 claim이 잡은 명령").containsExactly("pay-3");
        }
    }

    /** 늦은 워커의 넘기기: 운영 코드와 같은 문장이고, 잠글 키를 고르는 질의만 문을 지나간다. */
    private List<String> latePassOn() {
        List<String> keys = storage.sql().sql(gated(JdbcOrderingKeys.LOCK_KEYS_TO_PASS_ON)).param("gate", GATE)
                .param("limit", 10).query(String.class).list();
        if (!keys.isEmpty()) {
            storage.sql().sql(JdbcOrderingKeys.REWRITE_KEY_ROWS).param("keys", keys).update();
            storage.sql().sql(JdbcOrderingKeys.CLEAR_FINISHED_HEADS).param("keys", keys).update();
            storage.sql().sql(JdbcOrderingKeys.MARK_NEXT_HEADS).param("keys", keys).update();
        }
        return keys;
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 질의가 스냅숏을 잡은 뒤, 끝난 맨 앞을 읽기 전에 advisory lock {@code :gate}를 기다리게 한다. */
    private static String gated(String query) {
        String gated = query.replace("WHERE c.is_head",
                "WHERE (SELECT true FROM pg_advisory_xact_lock_shared(:gate)) AND c.is_head");
        if (gated.equals(query)) {
            throw new IllegalStateException("given 준비 실패: 질의에 문을 끼울 곳이 없다");
        }
        return gated;
    }

    /** 워커가 그 명령의 TB 결과를 쓴다. 핸들러 없이 저장소의 조건부 쓰기만 한다. */
    private boolean complete(ClaimedCommand claimed) {
        long submitCount = storage.sql().sql("SELECT submit_count FROM ledger_command WHERE id = :id")
                .param("id", claimed.rowId()).query(Long.class).single();
        CommandRow applied = new CommandRow(StoredState.APPLIED, claimed.leaseToken(), submitCount, Optional.of(Created),
                Optional.of(Created), 0);
        if (!storage.store().compareAndSet(new RowChange(claimed.rowId(), StoredState.CLAIMED, claimed.leaseToken(),
                applied, new RowChange.Stamp.Completion()))) {
            throw new IllegalStateException("given 준비 실패: " + claimed.commandId() + "의 결과를 쓰지 못했다");
        }
        return true;
    }

    /** 접수가 넣는 것과 같은 대기 행을 맨 앞 정하기 없이 넣는다. */
    private void insertPendingRow(String commandId, long seq) {
        storage.sql().sql("""
                INSERT INTO ledger_command (command_id, transfer_id, ordering_key, seq, kind, debit_account_id,
                    credit_account_id, amount, ledger, code, timeout_seconds, user_data_64, user_data_32,
                    content_hash, state)
                VALUES (:id, 900 + :seq, 'order-1', :seq, 'TRANSFER', 1, 2, 500, 1, 1, 0, 0, 0, 'hash', 'PENDING')
                """).param("id", commandId).param("seq", seq).update();
    }

    private List<String> claimedNow() {
        return relay.claim(10, LEASE).claimed().stream().map(ClaimedCommand::commandId).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("10초 안에 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리다 인터럽트되었다", e);
        }
    }

    private static LedgerCommand transfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }
}
