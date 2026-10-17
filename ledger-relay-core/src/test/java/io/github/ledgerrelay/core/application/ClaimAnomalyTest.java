package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 공개 메서드로는 만들 수 없는 행을 claim하는 경우(docs/failure-modes.md F9·F10). 접수 검사가 막는 상태라서
 * 계약(LedgerRelayContract) 대신 여기 두고, 저장소에 행을 직접 넣어 버그·DB 직접 수정·보관 정책 뒤의 상태를 만든다.
 */
@DisplayName("정상 경로에서 나올 수 없는 행을 claim하면 — 스펙 §5.2, docs/failure-modes.md")
class ClaimAnomalyTest {

    static final Duration LEASE = Duration.ofSeconds(30);

    final RecordingHandler handler = new RecordingHandler();
    InMemoryStorage storage;
    CommandLifecycle relay;

    @BeforeEach
    void givenAnEmptyRelay() {
        storage = InMemoryStorage.create();
        relay = new CommandLifecycle(storage.store(), storage.transactions(), new SequentialTransferIds(),
                handler, new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
    }

    @Nested
    @Order(1)
    @DisplayName("확정이 참조한 예약 행이 보관 정책으로 지워진 상태에서 — F10")
    class WhenTheReservationRowIsGone {

        @BeforeEach
        void givenCapture1WhoseHold1RowIsGone() {
            accepted(post("capture-1", "order-1", 2, "hold-1", 101));
        }

        @Test
        @DisplayName("claim하면 TB에 보낼 명령으로 잡힌다 — 멈추지 않고 TB가 판정하게 한다")
        void itIsClaimedForTigerBeetle() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "capture-1");
            assertNothingHalted(batch);
        }

        @Test
        @DisplayName("잡힌 확정은 접수 때 고정한 예약의 이체 ID를 싣는다 — 엉뚱한 예약을 가리킬 수 없다")
        void itCarriesThePendingIdFixedAtAcceptance() {
            // when
            ClaimedCommand capture = relay.claim(10, LEASE).claimed().get(0);

            // then
            assertThat(capture.skip()).as("건너뛸 대상인가").isFalse();
            assertThat(capture.submission().body().pendingTransferId()).as("확정이 가리키는 예약 이체 ID")
                    .contains(BigInteger.valueOf(101));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("같은 키에서 확정이 자기가 참조한 예약보다 앞 순번인 상태에서 — F9")
    class WhenThePostIsAheadOfItsReservation {

        @BeforeEach
        void givenCapture1AtSeq1AheadOfHold1AtSeq2() {
            accepted(reserve("hold-1", "order-1", 2));
            accepted(post("capture-1", "order-1", 1, "hold-1", 102));
        }

        @Test
        @DisplayName("claim하면 잡지 않고 멈춘 명령으로 알린다 — 먼저 보내면 잘못 거절되고 예약 금액이 묶인다")
        void claimingHaltsIt() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("claim하면 행이 멈춤이 되고 토큰이 1 오른다 — TB에는 보내지 않았다")
        void haltingParksTheRow() {
            // when
            relay.claim(10, LEASE);

            // then
            assertRow("capture-1", new CommandRow(StoredState.HALTED, 1, 0, Optional.empty(), Optional.empty(), 0));
        }

        @Test
        @DisplayName("조회하면 멈춤으로 보인다 — 접수됨으로 보여 원인을 놓치지 않게")
        void theQueryShowsItHalted() {
            // when
            relay.claim(10, LEASE);

            // then
            assertThat(relay.status("capture-1").map(CommandStatus::state)).as("capture-1의 조회 상태")
                    .contains(State.HALTED);
        }

        @Test
        @DisplayName("멈춘 명령은 다음 claim에서 다시 알리지 않는다 — 알림은 멈출 때 한 번이다")
        void itIsReportedOnce() {
            // given
            relay.claim(10, LEASE);

            // when
            ClaimBatch next = relay.claim(10, LEASE);

            // then
            assertNothingHalted(next);
        }

        @Test
        @DisplayName("멈춘 키의 뒤 명령은 운영자가 풀 때까지 기다린다 — 같은 키의 순서를 건너뛰지 않는다(G4)")
        void laterCommandsInTheKeyWait() {
            // given
            relay.claim(10, LEASE);

            // when
            ClaimBatch next = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(next);
        }

        @Test
        @DisplayName("다른 순서 키의 명령은 멈춘 명령과 상관없이 잡힌다")
        void otherKeysAreStillClaimed() {
            // given
            storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1")
                    .orderingKey("order-2").debit(1).credit(2).amount(500).ledger(1).code(1).build()));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertClaimed(batch, "pay-1");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("멈춘 명령이 있는 상태에서 다른 키의 명령이 기다리면 — F9")
    class WhileAKeyIsHalted {

        @BeforeEach
        void givenCapture1IsHaltedAndPay1Waits() {
            accepted(reserve("hold-1", "order-1", 2));
            accepted(post("capture-1", "order-1", 1, "hold-1", 102));
            relay.claim(10, LEASE);
            storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1")
                    .orderingKey("order-2").debit(1).credit(2).amount(500).ledger(1).code(1).build()));
        }

        @Test
        @DisplayName("한 번에 하나만 잡아도 그 하나는 다른 키의 명령이다 — 멈춘 명령은 limit을 차지하지 않는다")
        void theHaltedCommandDoesNotUseUpTheLimit() {
            // when
            ClaimBatch batch = relay.claim(1, LEASE);

            // then
            assertClaimed(batch, "pay-1");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("멈춘 확정을 운영자가 포기하면 — F9")
    class AbandoningTheHaltedPost {

        @BeforeEach
        void givenCapture1IsHaltedAheadOfHold1() {
            accepted(reserve("hold-1", "order-1", 2));
            accepted(post("capture-1", "order-1", 1, "hold-1", 102));
            relay.claim(10, LEASE);
        }

        @Test
        @DisplayName("건너뜀으로 끝나고, 핸들러가 '운영자 포기' 사유를 한 번 받는다 — TB에는 한 번도 가지 않았다")
        void itEndsSkippedAsAbandoned() {
            // when
            WriteResult result = relay.abandonHalted("capture-1");

            // then
            assertThat(result).as("포기 쓰기").isEqualTo(new WriteResult.Written());
            assertHandlerRanOnceAsAbandoned("capture-1");
        }

        @Test
        @DisplayName("키가 풀려 뒤에 있던 예약이 잡힌다")
        void theKeyIsReleased() {
            // given
            relay.abandonHalted("capture-1");

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "hold-1");
        }

        @Test
        @DisplayName("핸들러가 실패하면 멈춘 그대로 남고, 핸들러 실패가 돌아온다 — 운영자가 다시 포기할 수 있다")
        void aFailingHandlerLeavesItHalted() {
            // given
            handler.failNextCalls(1);

            // when
            WriteResult result = relay.abandonHalted("capture-1");

            // then
            assertThat(result).as("포기 쓰기").isInstanceOf(WriteResult.HandlerFailed.class);
            assertRow("capture-1", new CommandRow(StoredState.HALTED, 1, 0, Optional.empty(), Optional.empty(), 0));
        }
    }

    @Nested
    @Order(5)
    @DisplayName("멈추지 않은 명령을 포기하려 하면")
    class AbandoningSomethingElse {

        @Test
        @DisplayName("접수된 명령이면 거부되고, 아무것도 바뀌지 않는다")
        void anAcceptedCommandIsRefused() {
            // given
            storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1")
                    .orderingKey("order-1").debit(1).credit(2).amount(500).ledger(1).code(1).build()));

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("pay-1");

            // then
            assertThatThrownBy(abandoning).as("멈춘 명령이 아니라 거부되기를 기대했다")
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("HALTED");
            assertRow("pay-1", CommandRow.accepted());
        }

        @Test
        @DisplayName("접수한 적 없는 명령이면 거부된다")
        void anUnknownCommandIsRefused() {
            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("never-accepted");

            // then
            assertThatThrownBy(abandoning).as("없는 명령이라 거부되기를 기대했다")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never-accepted");
        }

        @Test
        @DisplayName("트랜잭션 안에서 부르면 거부된다 — 핸들러 실패가 바깥 트랜잭션에 묻히지 않게(G6)")
        void abandoningInsideATransactionIsRefused() {
            // given
            accepted(reserve("hold-1", "order-1", 2));
            accepted(post("capture-1", "order-1", 1, "hold-1", 102));
            relay.claim(10, LEASE);

            // when
            ThrowingCallable abandoningInside =
                    () -> storage.transactions().inTransaction(() -> relay.abandonHalted("capture-1"));

            // then
            assertThatThrownBy(abandoningInside).as("트랜잭션 안이라 거부되기를 기대했다")
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("트랜잭션 밖에서");
        }
    }

    // ── 행을 만드는 말: 접수 검사를 거치지 않고 저장소에 바로 넣는다. 이체 ID는 100+순번이다 ──────

    private static NewCommand reserve(String commandId, String orderingKey, long seq) {
        return new NewCommand(commandId, orderingKey, seq, Optional.empty(), BigInteger.valueOf(100 + seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    private static NewCommand post(String commandId, String orderingKey, long seq, String reservationCommandId,
            long pendingTransferId) {
        return new NewCommand(commandId, orderingKey, seq, Optional.of(reservationCommandId),
                BigInteger.valueOf(100 + seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(BigInteger.valueOf(pendingTransferId)), 0, 0));
    }

    // ── 전제와 확인의 말 ────────────────────────────────────────────

    /** 전제: 이 행이 새로 들어간다. */
    private void accepted(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + row.commandId() + "가 이미 있었다");
        }
    }

    private static void assertClaimed(ClaimBatch batch, String... commandIds) {
        assertThat(batch.claimed().stream().map(ClaimedCommand::commandId).toList()).as("잡힌 명령")
                .containsExactly(commandIds);
    }

    private static void assertNothingClaimed(ClaimBatch batch) {
        assertThat(batch.claimed()).as("잡힌 명령이 없기를 기대했다").isEmpty();
    }

    private static void assertHalted(ClaimBatch batch, String... commandIds) {
        assertThat(batch.halted()).as("멈춘 명령으로 알린 것").containsExactly(commandIds);
    }

    private static void assertNothingHalted(ClaimBatch batch) {
        assertThat(batch.halted()).as("멈춘 명령이 없기를 기대했다").isEmpty();
    }

    private void assertRow(String commandId, CommandRow expected) {
        CommandRow row = storage.transactions().inTransaction(() -> storage.store().find(commandId)).orElseThrow().row();
        assertThat(row).as("%s의 행", commandId).isEqualTo(expected);
    }

    private void assertHandlerRanOnceAsAbandoned(String commandId) {
        assertThat(handler.completedFor(commandId).stream().map(outcome -> outcome.state() + "/" + outcome.skipReason()
                .map(Enum::name).orElse("-")).toList())
                .as("%s에 대해 끝까지 실행된 핸들러 호출(상태/건너뛴 이유)", commandId)
                .containsExactly("SKIPPED/ABANDONED_BY_OPERATOR");
    }
}
