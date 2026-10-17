package io.github.ledgerrelay.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
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

    InMemoryStorage storage;
    CommandLifecycle relay;

    @BeforeEach
    void givenAnEmptyRelay() {
        storage = InMemoryStorage.create();
        relay = new CommandLifecycle(storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
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
        @DisplayName("claim해도 행은 막 접수된 그대로다 — 토큰도 오르지 않는다")
        void haltingLeavesTheRow() {
            // when
            relay.claim(10, LEASE);

            // then
            assertStillJustAccepted("capture-1");
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

    private void assertStillJustAccepted(String commandId) {
        CommandRow row = storage.transactions().inTransaction(() -> storage.store().find(commandId)).orElseThrow().row();
        assertThat(row).as("%s의 행 — 막 접수된 그대로(토큰 0)", commandId).isEqualTo(CommandRow.accepted());
    }
}
