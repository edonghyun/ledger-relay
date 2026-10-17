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
 * 공개 메서드로는 만들 수 없는 경우라 계약(LedgerRelayContract) 대신 여기 둔다(P2a-14). 예약 행은 접수 검사를
 * 통과한 뒤에만 지워질 수 있으므로(보관 정책, 02b), 저장소에 예약 없는 확정을 직접 넣어 그 상태를 만든다.
 */
@DisplayName("claim이 멈추는 경우 — 스펙 §5.2 HALT, P2a-15")
class ClaimHaltTest {

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
    @DisplayName("참조한 예약 행이 사라진 확정이 접수된 상태에서")
    class WhenTheReservationRowIsGone {

        @BeforeEach
        void givenCapture1WhoseHold1IsGone() {
            acceptedWithoutItsReservation("capture-1", "order-1", "hold-1");
        }

        @Test
        @DisplayName("claim하면 잡지 않고 멈춘 명령으로 알린다 — TB에 보내면 엉뚱한 예약을 확정할 수 있다")
        void claimingHaltsIt() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("claim해도 행은 막 접수된 그대로다 — 토큰도 오르지 않고, 사람이 볼 때까지 아무것도 바꾸지 않는다")
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
            assertThat(batch.claimed().stream().map(ClaimedCommand::commandId).toList())
                    .as("잡힌 명령").containsExactly("pay-1");
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 전제: 확정이 접수되어 있고, 그것이 참조한 예약 행은 저장소에 없다. */
    private void acceptedWithoutItsReservation(String commandId, String orderingKey, String reservationCommandId) {
        NewCommand post = new NewCommand(commandId, orderingKey, 2, Optional.of(reservationCommandId),
                BigInteger.valueOf(102), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(BigInteger.valueOf(101)), 0, 0));
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(post)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + commandId + "가 이미 있었다");
        }
    }

    private static void assertHalted(ClaimBatch batch, String... commandIds) {
        assertThat(batch.halted()).as("멈춘 명령으로 알린 것").containsExactly(commandIds);
    }

    private static void assertNothingClaimed(ClaimBatch batch) {
        assertThat(batch.claimed()).as("잡힌 명령이 없기를 기대했다").isEmpty();
    }

    private void assertStillJustAccepted(String commandId) {
        CommandRow row = storage.transactions().inTransaction(() -> storage.store().find(commandId)).orElseThrow().row();
        assertThat(row).as("%s의 행 — 막 접수된 그대로(토큰 0)", commandId).isEqualTo(CommandRow.accepted());
    }
}
