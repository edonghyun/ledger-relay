package io.github.ledgerrelay.core;

import static io.github.ledgerrelay.core.CommandState.APPLIED;
import static io.github.ledgerrelay.core.CommandState.CLAIMED;
import static io.github.ledgerrelay.core.CommandState.PENDING;
import static io.github.ledgerrelay.core.CommandState.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("순서 게이트 — 스펙 G4, §5.2")
class OrderingGateTest {

    @Nested
    @DisplayName("같은 키에 앞 명령이 있을 때")
    class EarlierCommandsInTheSameKey {

        @Test
        @DisplayName("앞 명령이 아직 접수 상태(PENDING)면 다음 명령은 기다린다")
        void waitsForAnAcceptedEarlierCommand() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-2"), 2, queue(
                    queued("pay-1", 1, PENDING)));

            // then
            assertWaits(decision);
        }

        @Test
        @DisplayName("G2: 앞 명령이 처리 중(CLAIMED, 결과 미확정 포함)이면 다음 명령(재시도 명령 포함)은 기다린다")
        void waitsForAnInFlightEarlierCommand() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1-retry"), 2, queue(
                    queued("pay-1", 1, CLAIMED)));

            // then
            assertWaits(decision);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CommandState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("앞 명령이 최종 상태면 결과와 관계없이 다음 명령을 제출한다 — 거절된 앞 명령이 키를 막지 않는다")
        void submitsAfterAFinishedEarlierCommand(CommandState finished) {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-2"), 2, queue(
                    queued("pay-1", 1, finished)));

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("seq에 빈 번호가 있어도 대소만 비교한다 (§5.1)")
        void seqGapsDoNotMatter() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-7"), 7, queue(
                    queued("pay-1", 1, APPLIED),
                    queued("pay-4", 4, REJECTED)));

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("뒤 명령이 미완료여도 앞 명령은 막히지 않는다")
        void laterCommandsDoNotBlock() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, queue(
                    queued("pay-2", 2, PENDING)));

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("목록에 후보 자신이 들어 있어도 스스로를 막지 않는다")
        void doesNotBlockItself() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, queue(
                    queued("pay-1", 1, PENDING)));

            // then
            assertSubmits(decision);
        }
    }

    @Nested
    @DisplayName("확정·해제가 참조한 예약이")
    class ReferencedReservation {

        @Test
        @DisplayName("적용(APPLIED)됐으면 확정을 제출한다")
        void submitsWhenTheReservationIsApplied() {
            // when
            GateDecision decision = OrderingGate.decide(aPostOf("hold-1"), 2, queue(
                    queued("hold-1", 1, APPLIED)));

            // then
            assertSubmits(decision);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CommandState.class, names = {"REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("적용되지 않은 최종 상태로 끝났으면 TB에 보내지 않고 건너뛴다(SKIPPED)")
        void skipsWhenTheReservationEndedUnapplied(CommandState unapplied) {
            // when
            GateDecision decision = OrderingGate.decide(aVoidOf("hold-1"), 2, queue(
                    queued("hold-1", 1, unapplied)));

            // then
            assertSkips(decision);
        }

        @Test
        @DisplayName("보관 정책으로 지워져 목록에 없으면 TB 판정에 맡기고 제출한다 (P1-8)")
        void submitsWhenTheReservationRecordIsGone() {
            // when
            GateDecision decision = OrderingGate.decide(aPostOf("hold-1"), 9, queue(
                    queued("pay-5", 5, APPLIED)));

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("거절됐더라도 다른 앞 명령이 미완료면 건너뛰지 않고 먼저 기다린다")
        void waitsForOtherEarlierCommandsBeforeSkipping() {
            // when
            GateDecision decision = OrderingGate.decide(aPostOf("hold-1"), 3, queue(
                    queued("hold-1", 1, REJECTED),
                    queued("pay-2", 2, PENDING)));

            // then
            assertWaits(decision);
        }
    }

    @Nested
    @DisplayName("게이트 결정은")
    class DecisionToEvent {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                OPEN,   CLAIM_WITH_GATE_OPEN
                CLOSED, CLAIM_WITH_GATE_CLOSED
                SKIP,   CLAIM_WITH_RESERVATION_NOT_APPLIED
                """)
        @DisplayName("전이표의 claim 사건 셋 중 하나가 된다 (§5.2 → §4.3)")
        void becomesAClaimEvent(GateDecision decision, CommandEvent expected) {
            assertThat(decision.claimEvent()).isEqualTo(expected);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static LedgerCommand aTransfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();
    }

    private static LedgerCommand aPostOf(String reservationCommandId) {
        return LedgerCommand.post("capture-of-" + reservationCommandId).orderingKey("order-42")
                .reservation(reservationCommandId).build();
    }

    private static LedgerCommand aVoidOf(String reservationCommandId) {
        return LedgerCommand.voidReservation("release-of-" + reservationCommandId).orderingKey("order-42")
                .reservation(reservationCommandId).build();
    }

    private static List<QueuedCommand> queue(QueuedCommand... commands) {
        return List.of(commands);
    }

    private static QueuedCommand queued(String commandId, long seq, CommandState state) {
        return new QueuedCommand(commandId, seq, state);
    }

    private static void assertSubmits(GateDecision decision) {
        assertThat(decision).as("제출하기(OPEN)를 기대했다").isEqualTo(GateDecision.OPEN);
    }

    private static void assertWaits(GateDecision decision) {
        assertThat(decision).as("앞 명령을 기다리기(CLOSED)를 기대했다").isEqualTo(GateDecision.CLOSED);
    }

    private static void assertSkips(GateDecision decision) {
        assertThat(decision).as("TB에 보내지 않고 건너뛰기(SKIP)를 기대했다").isEqualTo(GateDecision.SKIP);
    }
}
