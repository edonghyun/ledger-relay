package io.github.ledgerrelay.core;

import static io.github.ledgerrelay.core.CommandState.APPLIED;
import static io.github.ledgerrelay.core.CommandState.PENDING;
import static io.github.ledgerrelay.core.CommandState.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("순서 게이트 — 스펙 G4, §5.2")
class OrderingGateTest {

    @Nested
    @Order(1)
    @DisplayName("같은 키에 앞 명령이 없으면")
    class NoEarlierCommand {

        @Test
        @DisplayName("바로 제출한다")
        void submitsRightAway() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, sameKey());

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("뒤 명령이 미완료여도 제출한다")
        void laterCommandsDoNotBlock() {
            // given: 뒤에 접수된 pay-2(seq 2)가 아직 PENDING이다
            List<QueuedCommand> sameKey = sameKey(queued("pay-2", 2, PENDING));

            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, sameKey);

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("같은 키 명령들 속에 자기 자신(PENDING)이 있어도 제출한다")
        void doesNotBlockItself() {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("pay-1", 1, PENDING));

            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, sameKey);

            // then
            assertSubmits(decision);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("같은 키에 미완료인 앞 명령이 있으면")
    class UnfinishedEarlierCommand {

        @ParameterizedTest(name = "앞 명령이 {0}")
        @EnumSource(value = CommandState.class, names = {"PENDING", "CLAIMED"})
        @DisplayName("G2: 앞 명령이 끝날 때까지 기다린다 — 결과가 미확정이어도, 뒤 명령이 재시도여도")
        void waitsForTheEarlierCommand(CommandState unfinished) {
            // given: pay-1(seq 1)이 아직 끝나지 않았다
            List<QueuedCommand> sameKey = sameKey(queued("pay-1", 1, unfinished));

            // when: 그 재시도인 pay-1-retry(seq 2)를 claim하면
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1-retry"), 2, sameKey);

            // then
            assertWaits(decision);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("같은 키의 앞 명령이 모두 최종 상태면")
    class FinishedEarlierCommands {

        @ParameterizedTest(name = "앞 명령이 {0}")
        @EnumSource(value = CommandState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("결과와 관계없이 제출한다 — 거절된 앞 명령이 키를 막지 않는다")
        void submitsWhateverTheEarlierOutcome(CommandState finished) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("pay-1", 1, finished));

            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-2"), 2, sameKey);

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("seq에 빈 번호(2·3·5·6)가 있어도 제출한다 (§5.1)")
        void seqGapsDoNotMatter() {
            // given
            List<QueuedCommand> sameKey = sameKey(
                    queued("pay-1", 1, APPLIED),
                    queued("pay-4", 4, REJECTED));

            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-7"), 7, sameKey);

            // then
            assertSubmits(decision);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("예약(reserve)은")
    class Reserve {

        @Test
        @DisplayName("참조할 예약이 없으므로, 앞 명령이 끝났으면 이체처럼 제출한다")
        void submitsLikeATransfer() {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("pay-1", 1, APPLIED));

            // when
            GateDecision decision = OrderingGate.decide(aReserve("hold-2"), 2, sameKey);

            // then
            assertSubmits(decision);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("확정·해제가 참조한 예약이")
    class ReferencedReservation {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CommandKind.class, names = {"POST", "VOID"})
        @DisplayName("적용(APPLIED)됐으면 제출한다")
        void submitsWhenTheReservationIsApplied(CommandKind kind) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 1, APPLIED));

            // when
            GateDecision decision = OrderingGate.decide(referencing(kind, "hold-1"), 2, sameKey);

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("적용됐으면, 같은 키에 거절된 다른 명령이 함께 있어도 제출한다 — 다른 명령의 결과를 예약의 결과로 읽지 않는다")
        void readsOnlyTheReferencedReservation() {
            // given: 같은 키에 거절된 pay-0과 적용된 hold-1이 있다
            List<QueuedCommand> sameKey = sameKey(
                    queued("pay-0", 1, REJECTED),
                    queued("hold-1", 2, APPLIED));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 3, sameKey);

            // then
            assertSubmits(decision);
        }

        @ParameterizedTest(name = "{0}, 예약이 {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                POST, REJECTED
                POST, CONFLICT
                POST, SKIPPED
                VOID, REJECTED
                VOID, CONFLICT
                VOID, SKIPPED
                """)
        @DisplayName("적용되지 않은 최종 상태로 끝났으면 TB에 보내지 않고 건너뛴다(SKIPPED)")
        void skipsWhenTheReservationEndedUnapplied(CommandKind kind, CommandState unapplied) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 1, unapplied));

            // when
            GateDecision decision = OrderingGate.decide(referencing(kind, "hold-1"), 2, sameKey);

            // then
            assertSkips(decision);
        }

        @ParameterizedTest(name = "예약이 {0}")
        @EnumSource(value = CommandState.class, names = {"PENDING", "CLAIMED"})
        @DisplayName("아직 끝나지 않았으면 끝날 때까지 기다린다")
        void waitsForAnUnfinishedReservation(CommandState unfinished) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 1, unfinished));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 2, sameKey);

            // then
            assertWaits(decision);
        }

        @Test
        @DisplayName("거절됐더라도 다른 앞 명령이 미완료면 건너뛰지 않고 먼저 기다린다")
        void waitsForOtherEarlierCommandsBeforeSkipping() {
            // given: hold-1은 거절됐고, 그 뒤의 pay-2는 아직 PENDING이다
            List<QueuedCommand> sameKey = sameKey(
                    queued("hold-1", 1, REJECTED),
                    queued("pay-2", 2, PENDING));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 3, sameKey);

            // then
            assertWaits(decision);
        }
    }

    @Nested
    @Order(6)
    @DisplayName("정상 경로에서 나올 수 없는 입력이면")
    class Anomalies {

        @Test
        @DisplayName("참조한 예약이 같은 키 명령들 속에 없으면 제출하지 않고 멈춘 뒤 알린다 — 같은 ID의 엉뚱한 예약을 확정하지 않게")
        void haltsWhenTheReservationIsMissing() {
            // given: hold-1은 없고, 거절된 다른 명령 pay-5만 있다
            List<QueuedCommand> sameKey = sameKey(queued("pay-5", 5, REJECTED));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 9, sameKey);

            // then
            assertHalts(decision);
        }

        @ParameterizedTest(name = "예약이 {0}")
        @EnumSource(value = CommandState.class, names = {"PENDING", "CLAIMED"})
        @DisplayName("참조한 예약이 자기보다 뒤 seq에서 아직 끝나지 않았으면 제출하지 않고 멈춘 뒤 알린다")
        void haltsWhenTheReservationComesLater(CommandState unfinished) {
            // given: 예약 hold-1(seq 3)이 확정(seq 2)보다 뒤에 있다. 접수 검사(§5.1-3)가 막았어야 하는 순서다
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 3, unfinished));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 2, sameKey);

            // then
            assertHalts(decision);
        }
    }

    @Nested
    @Order(7)
    @DisplayName("게이트 결정은")
    class Decisions {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                OPEN,   CLAIM_WITH_GATE_OPEN
                SKIP,   CLAIM_WITH_GATE_OPEN
                CLOSED, CLAIM_WITH_GATE_CLOSED
                HALT,   CLAIM_WITH_GATE_CLOSED
                """)
        @DisplayName("전이표의 claim 사건이 된다 — 건너뛸 명령도 먼저 claim하고, 멈춘 명령은 그대로 둔다 (§5.2 → §4.3)")
        void becomesAClaimEvent(GateDecision decision, CommandEvent expected) {
            assertThat(decision.claimEvent()).as("%s 결정의 claim 사건", decision).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(GateDecision.class)
        @DisplayName("멈춤(HALT)만 사람에게 알린다")
        void onlyHaltAlerts(GateDecision decision) {
            assertThat(decision.alerts()).as("%s 결정의 알림 여부", decision).isEqualTo(decision == GateDecision.HALT);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static LedgerCommand aTransfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();
    }

    private static LedgerCommand aReserve(String commandId) {
        return LedgerCommand.reserve(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).timeoutSeconds(30).build();
    }

    /** {@code reservationCommandId}를 참조하는 확정(POST) 또는 해제(VOID). */
    private static LedgerCommand referencing(CommandKind kind, String reservationCommandId) {
        LedgerCommand.Builder builder = kind == CommandKind.POST
                ? LedgerCommand.post("capture-of-" + reservationCommandId)
                : LedgerCommand.voidReservation("release-of-" + reservationCommandId);
        return builder.orderingKey("order-42").reservation(reservationCommandId).build();
    }

    /** 게이트가 보는 같은 순서 키의 명령들. */
    private static List<QueuedCommand> sameKey(QueuedCommand... commands) {
        return List.of(commands);
    }

    private static QueuedCommand queued(String commandId, long seq, CommandState state) {
        return new QueuedCommand(commandId, seq, state);
    }

    private static void assertSubmits(GateDecision decision) {
        expect(decision, GateDecision.OPEN, "제출하기");
    }

    private static void assertWaits(GateDecision decision) {
        expect(decision, GateDecision.CLOSED, "앞 명령을 기다리기");
    }

    private static void assertSkips(GateDecision decision) {
        expect(decision, GateDecision.SKIP, "TB에 보내지 않고 건너뛰기");
    }

    private static void assertHalts(GateDecision decision) {
        expect(decision, GateDecision.HALT, "제출하지 않고 멈춘 뒤 알리기");
    }

    private static void expect(GateDecision actual, GateDecision expected, String meaning) {
        if (actual != expected) {
            throw new AssertionError(meaning + "(" + expected + ")를 기대했지만 실제 결과는 " + actual);
        }
    }
}
