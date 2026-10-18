package io.github.ledgerrelay.core.domain;

import static io.github.ledgerrelay.core.domain.StoredState.APPLIED;
import static io.github.ledgerrelay.core.domain.StoredState.PENDING;
import static io.github.ledgerrelay.core.domain.StoredState.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.HaltReason;
import io.github.ledgerrelay.core.LedgerCommand;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Ordering gate — G4")
class OrderingGateTest {

    @Nested
    @Order(1)
    @DisplayName("When there is no earlier command on the same key")
    class NoEarlierCommand {

        @Test
        @DisplayName("It submits right away")
        void submitsRightAway() {
            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, sameKey());

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("It submits even if a later command is unfinished")
        void laterCommandsDoNotBlock() {
            // given: pay-2 (seq 2), accepted later, is still PENDING
            List<QueuedCommand> sameKey = sameKey(queued("pay-2", 2, PENDING));

            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1"), 1, sameKey);

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("It submits even if the commands on the same key include itself (PENDING)")
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
    @DisplayName("When an earlier command on the same key is unfinished")
    class UnfinishedEarlierCommand {

        @ParameterizedTest(name = "earlier command {0}")
        @EnumSource(value = StoredState.class, names = {"PENDING", "CLAIMED"})
        @DisplayName("G2: it waits until the earlier command ends — even if that result is still unknown, and even if the later command is a retry")
        void waitsForTheEarlierCommand(StoredState unfinished) {
            // given: pay-1 (seq 1) has not ended yet
            List<QueuedCommand> sameKey = sameKey(queued("pay-1", 1, unfinished));

            // when: its retry pay-1-retry (seq 2) is claimed
            GateDecision decision = OrderingGate.decide(aTransfer("pay-1-retry"), 2, sameKey);

            // then
            assertWaits(decision);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When every earlier command on the same key is in a final state")
    class FinishedEarlierCommands {

        @ParameterizedTest(name = "earlier command {0}")
        @EnumSource(value = StoredState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("It submits whatever the result — a rejected earlier command does not block the key")
        void submitsWhateverTheEarlierOutcome(StoredState finished) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("pay-1", 1, finished));

            // when
            GateDecision decision = OrderingGate.decide(aTransfer("pay-2"), 2, sameKey);

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("It submits even if seq has gaps (2, 3, 5, 6)")
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
    @DisplayName("A reservation (reserve)")
    class Reserve {

        @Test
        @DisplayName("Has no reservation to refer to, so it submits like a transfer once the earlier commands have ended")
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
    @DisplayName("When the reservation a post or void refers to")
    class ReferencedReservation {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CommandKind.class, names = {"POST", "VOID"})
        @DisplayName("Was applied (APPLIED), it submits")
        void submitsWhenTheReservationIsApplied(CommandKind kind) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 1, APPLIED));

            // when
            GateDecision decision = OrderingGate.decide(referencing(kind, "hold-1"), 2, sameKey);

            // then
            assertSubmits(decision);
        }

        @Test
        @DisplayName("Was applied, it submits even if a rejected command sits on the same key — another command's result is not read as the reservation's")
        void readsOnlyTheReferencedReservation() {
            // given: the same key holds the rejected pay-0 and the applied hold-1
            List<QueuedCommand> sameKey = sameKey(
                    queued("pay-0", 1, REJECTED),
                    queued("hold-1", 2, APPLIED));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 3, sameKey);

            // then
            assertSubmits(decision);
        }

        @ParameterizedTest(name = "{0}, reservation {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                POST, REJECTED
                POST, CONFLICT
                POST, SKIPPED
                VOID, REJECTED
                VOID, CONFLICT
                VOID, SKIPPED
                """)
        @DisplayName("Ended in a final state that is not applied, it is skipped (SKIPPED) without being sent to TB")
        void skipsWhenTheReservationEndedUnapplied(CommandKind kind, StoredState unapplied) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 1, unapplied));

            // when
            GateDecision decision = OrderingGate.decide(referencing(kind, "hold-1"), 2, sameKey);

            // then
            assertSkips(decision);
        }

        @ParameterizedTest(name = "reservation {0}")
        @EnumSource(value = StoredState.class, names = {"PENDING", "CLAIMED"})
        @DisplayName("Has not ended yet, it waits until it ends")
        void waitsForAnUnfinishedReservation(StoredState unfinished) {
            // given
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 1, unfinished));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 2, sameKey);

            // then
            assertWaits(decision);
        }

        @Test
        @DisplayName("Was rejected but another earlier command is unfinished, it waits first instead of skipping")
        void waitsForOtherEarlierCommandsBeforeSkipping() {
            // given: hold-1 was rejected, and pay-2 after it is still PENDING
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
    @DisplayName("When the input cannot occur on the normal path")
    class Anomalies {

        @Test
        @DisplayName("If the referenced reservation is not among the commands on the same key, it halts instead of submitting — if the reservation ended in conflict, someone else's transfer has the same TB id")
        void haltsWhenTheReservationIsMissing() {
            // given: hold-1 is absent; only another rejected command pay-5 is there. The retention policy does not
            // delete a reservation that an unfinished post refers to
            List<QueuedCommand> sameKey = sameKey(queued("pay-5", 5, REJECTED));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 9, sameKey);

            // then
            assertHalts(decision);
        }

        @ParameterizedTest(name = "reservation {0}")
        @EnumSource(value = StoredState.class, names = {"PENDING", "CLAIMED"})
        @DisplayName("If the referenced reservation has a later seq and is not finished yet, it halts instead of submitting and then alerts")
        void haltsWhenTheReservationComesLater(StoredState unfinished) {
            // given: reservation hold-1 (seq 3) comes after the post (seq 2). The acceptance checks should have stopped
            // this order
            List<QueuedCommand> sameKey = sameKey(queued("hold-1", 3, unfinished));

            // when
            GateDecision decision = OrderingGate.decide(referencing(CommandKind.POST, "hold-1"), 2, sameKey);

            // then
            assertHalts(decision);
        }
    }

    @Nested
    @Order(7)
    @DisplayName("A gate decision")
    class Decisions {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                OPEN,   CLAIM_WITH_GATE_OPEN
                SKIP,   CLAIM_WITH_GATE_OPEN
                CLOSED, CLAIM_WITH_GATE_CLOSED
                HALT,   CLAIM_WITH_GATE_HALTED
                """)
        @DisplayName("Becomes a claim event of the transition table — a command to be skipped is also claimed first, and a command to be halted becomes a halt event (→)")
        void becomesAClaimEvent(GateDecision decision, CommandEvent expected) {
            // when
            CommandEvent event = decision.claimEvent();

            // then
            assertThat(event).as("claim event of decision %s", decision).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(GateDecision.class)
        @DisplayName("Only halt (HALT) alerts a person")
        void onlyHaltAlerts(GateDecision decision) {
            // when
            boolean alerts = decision.alerts();

            // then
            assertThat(alerts).as("whether decision %s alerts", decision).isEqualTo(decision == GateDecision.HALT);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    @Nested
    @Order(8)
    @DisplayName("When asked why a command halted — so the operator knows what to fix")
    class WhyHalted {

        @Test
        @DisplayName("If the referenced reservation is not among the commands on the same key, the reason is reservation missing")
        void aMissingReservationIsExplained() {
            // when
            HaltReason reason = OrderingGate.explainHalt(CommandKind.POST, java.util.Optional.of("hold-1"), 2,
                    sameKey(queued("capture-1", 2, PENDING)));

            // then
            assertThat(reason).as("halt reason").isEqualTo(HaltReason.RESERVATION_MISSING);
        }

        @Test
        @DisplayName("If the referenced reservation is not sequenced before the post, the reason is reservation out of order")
        void aReservationNotEarlierIsExplained() {
            // when
            HaltReason reason = OrderingGate.explainHalt(CommandKind.POST, java.util.Optional.of("hold-1"), 1,
                    sameKey(queued("capture-1", 1, PENDING), queued("hold-1", 2, PENDING)));

            // then
            assertThat(reason).as("halt reason").isEqualTo(HaltReason.RESERVATION_NOT_EARLIER);
        }
    }

    private static LedgerCommand aTransfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();
    }

    private static LedgerCommand aReserve(String commandId) {
        return LedgerCommand.reserve(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).timeoutSeconds(30).build();
    }

    /** A post (POST) or void (VOID) that refers to {@code reservationCommandId}. */
    private static LedgerCommand referencing(CommandKind kind, String reservationCommandId) {
        LedgerCommand.Builder builder = kind == CommandKind.POST
                ? LedgerCommand.post("capture-of-" + reservationCommandId)
                : LedgerCommand.voidReservation("release-of-" + reservationCommandId);
        return builder.orderingKey("order-42").reservation(reservationCommandId).build();
    }

    /** The commands on the same ordering key that the gate looks at. */
    private static List<QueuedCommand> sameKey(QueuedCommand... commands) {
        return List.of(commands);
    }

    private static QueuedCommand queued(String commandId, long seq, StoredState state) {
        return new QueuedCommand(commandId, seq, state);
    }

    private static void assertSubmits(GateDecision decision) {
        expect(decision, GateDecision.OPEN, "submitting");
    }

    private static void assertWaits(GateDecision decision) {
        expect(decision, GateDecision.CLOSED, "waiting for the earlier command");
    }

    private static void assertSkips(GateDecision decision) {
        expect(decision, GateDecision.SKIP, "skipping without sending to TB");
    }

    private static void assertHalts(GateDecision decision) {
        expect(decision, GateDecision.HALT, "halting instead of submitting, then alerting");
    }

    private static void expect(GateDecision actual, GateDecision expected, String meaning) {
        if (actual != expected) {
            throw new AssertionError(meaning + " (" + expected + ") was expected, but the actual result is " + actual);
        }
    }
}
