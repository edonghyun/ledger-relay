package io.github.ledgerrelay.core.domain;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static io.github.ledgerrelay.core.LedgerResultCode.ExistsWithDifferentAmount;
import static io.github.ledgerrelay.core.LedgerResultCode.IdAlreadyFailed;
import static io.github.ledgerrelay.core.domain.Transition.Effect.BACKOFF;
import static io.github.ledgerrelay.core.domain.Transition.Effect.COUNT_HANDLER_FAILURE;
import static io.github.ledgerrelay.core.domain.Transition.Effect.INCREMENT_LEASE_TOKEN;
import static io.github.ledgerrelay.core.domain.Transition.Effect.KEEP_KNOWN_RESULT;
import static io.github.ledgerrelay.core.domain.Transition.Effect.RECORD_SUBMISSION;
import static io.github.ledgerrelay.core.domain.Transition.Effect.RUN_HANDLER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.SkipReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("State machine that handles the token — G5")
class CommandStateMachineTest {

    @Nested
    @Order(1)
    @DisplayName("When a command is claimed")
    class Claiming {

        @ParameterizedTest(name = "gate {0}")
        @EnumSource(value = GateDecision.class, names = {"OPEN", "SKIP"})
        @DisplayName("When the gate is open or the command is to be skipped, it becomes in progress and the token goes up by 1")
        void anOpenOrSkippingGateClaims(GateDecision gate) {
            // when
            StepResult result = CommandStateMachine.apply(CommandRow.accepted(), new Step.Claim(gate, false));

            // then
            assertMoved(result, new CommandRow(StoredState.CLAIMED, 1, 0, Optional.empty(), Optional.empty(), 0),
                    INCREMENT_LEASE_TOKEN);
        }

        @ParameterizedTest(name = "gate {0}")
        @EnumSource(value = GateDecision.class, names = "CLOSED")
        @DisplayName("When the gate is closed, nothing happens")
        void aClosedGateLeavesTheRow(GateDecision gate) {
            // when
            StepResult result = CommandStateMachine.apply(CommandRow.accepted(), new Step.Claim(gate, false));

            // then
            assertThat(result).as("result of claiming at gate %s", gate).isEqualTo(new StepResult.Unchanged());
        }

        @Test
        @DisplayName("When the gate halts, the command becomes halted and the token goes up by 1 — nothing is sent to TB")
        void aHaltedGateHaltsTheCommand() {
            // when
            StepResult result = CommandStateMachine.apply(CommandRow.accepted(), new Step.Claim(GateDecision.HALT, false));

            // then
            assertMoved(result, new CommandRow(StoredState.HALTED, 1, 0, Optional.empty(), Optional.empty(), 0),
                    INCREMENT_LEASE_TOKEN);
        }

        @Test
        @DisplayName("An in-progress command whose lease ran out is claimed again, and the raised token fences the previous worker's writes")
        void anExpiredLeaseIsClaimedAgain() {
            // given
            CommandRow heldByWorker3 = new CommandRow(StoredState.CLAIMED, 3, 1, Optional.empty(), Optional.empty(), 0);

            // when
            StepResult result = CommandStateMachine.apply(heldByWorker3, new Step.Claim(GateDecision.OPEN, true));

            // then
            assertMoved(result, new CommandRow(StoredState.CLAIMED, 4, 1, Optional.empty(), Optional.empty(), 0),
                    INCREMENT_LEASE_TOKEN);
        }

        @Test
        @DisplayName("Claiming an in-progress command with a live lease is refused as a bug")
        void claimingALiveLeaseIsABug() {
            // given
            CommandRow held = new CommandRow(StoredState.CLAIMED, 3, 0, Optional.empty(), Optional.empty(), 0);

            // when
            ThrowingCallable claiming = () -> CommandStateMachine.apply(held, new Step.Claim(GateDecision.OPEN, false));

            // then
            assertBug(claiming);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED", "HALTED"})
        @DisplayName("Claiming a finished or halted command is refused as a bug")
        void claimingAFinishedCommandIsABug(StoredState finished) {
            // given
            CommandRow done = rowIn(finished, 2, 1);

            // when
            ThrowingCallable claiming = () -> CommandStateMachine.apply(done, new Step.Claim(GateDecision.OPEN, false));

            // then
            assertBug(claiming);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When writing with the current token")
    class WritingWithTheCurrentToken {

        static final CommandRow HELD_WITH_TOKEN_5 =
                new CommandRow(StoredState.CLAIMED, 5, 0, Optional.empty(), Optional.empty(), 0);

        @Test
        @DisplayName("Recording a submission keeps it in progress and raises the submit count by 1")
        void recordingASubmissionCountsIt() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.MarkSubmitted(5));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.CLAIMED, 5, 1, Optional.empty(), Optional.empty(), 0),
                    RECORD_SUBMISSION);
        }

        @Test
        @DisplayName("Completing with success (Created) makes it applied, keeps the result code, and runs the handler")
        void completingWithSuccessApplies() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Complete(5, Created));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.APPLIED, 5, 0, Optional.empty(), Optional.of(Created), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("Completing with insufficient funds (ExceedsCredits) makes it rejected")
        void completingWithABusinessRejectionRejects() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Complete(5, ExceedsCredits));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.REJECTED, 5, 0, Optional.empty(), Optional.of(ExceedsCredits), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("Completing with a content conflict makes it a conflict")
        void completingWithAConflictConflicts() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Complete(5, ExistsWithDifferentAmount));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.CONFLICT, 5, 0, Optional.empty(), Optional.of(ExistsWithDifferentAmount), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("Skipping makes it skipped and runs the handler with reason 'reservation not applied' and no result code")
        void skippingSkips() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Skip(5));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.SKIPPED, 5, 0, Optional.empty(), Optional.empty(), 0,
                            Optional.of(SkipReason.RESERVATION_NOT_APPLIED)),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("Releasing for lack of a reply returns it to accepted to wait for backoff, and the token and submit count stay the same")
        void releasingReturnsToTheQueue() {
            // given
            CommandRow submittedOnce = new CommandRow(StoredState.CLAIMED, 5, 1, Optional.empty(), Optional.empty(), 0);

            // when
            StepResult result = CommandStateMachine.apply(submittedOnce, new Step.Release(5));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.PENDING, 5, 1, Optional.empty(), Optional.empty(), 0),
                    BACKOFF);
        }

        @Test
        @DisplayName("When the handler fails, it returns to accepted, keeps the TB result it received, and counts the failure")
        void aHandlerFailureKeepsTheResult() {
            // given
            CommandRow failedTwice = new CommandRow(StoredState.CLAIMED, 5, 1, Optional.empty(), Optional.empty(), 2);

            // when
            StepResult result = CommandStateMachine.apply(failedTwice, new Step.HandlerFailed(5, Optional.of(ExceedsCredits)));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.PENDING, 5, 1, Optional.of(ExceedsCredits), Optional.empty(), 3),
                    BACKOFF, KEEP_KNOWN_RESULT, COUNT_HANDLER_FAILURE);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When a TB result is already known")
    class WithAKnownResult {

        static final CommandRow HELD_KNOWING_EXCEEDS_CREDITS =
                new CommandRow(StoredState.CLAIMED, 6, 1, Optional.of(ExceedsCredits), Optional.empty(), 1);

        @Test
        @DisplayName("Completing with that result makes it rejected and runs the handler again — nothing is sent to TB again")
        void completesWithTheKnownResult() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_KNOWING_EXCEEDS_CREDITS, new Step.Complete(6, ExceedsCredits));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.REJECTED, 6, 1, Optional.of(ExceedsCredits), Optional.of(ExceedsCredits), 1),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("Recording a TB submission is refused as a bug — sending again would overwrite the original reason with IdAlreadyFailed")
        void submittingAgainIsABug() {
            // when
            ThrowingCallable writing = () -> CommandStateMachine.apply(HELD_KNOWING_EXCEEDS_CREDITS, new Step.MarkSubmitted(6));

            // then
            assertBug(writing);
        }

        @Test
        @DisplayName("Completing with a different code is refused as a bug")
        void completingWithAnotherCodeIsABug() {
            // when
            ThrowingCallable writing = () -> CommandStateMachine.apply(HELD_KNOWING_EXCEEDS_CREDITS, new Step.Complete(6, IdAlreadyFailed));

            // then
            assertBug(writing);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When writing with an old token or to a row that is not in progress — G5")
    class FencedWrites {

        static List<Step.Write> everyWriteWithToken(long token) {
            return List.of(new Step.MarkSubmitted(token), new Step.Complete(token, Created), new Step.Skip(token),
                    new Step.Release(token), new Step.HandlerFailed(token, Optional.of(Created)));
        }

        static Stream<Step.Write> writesWithTheOldToken4() {
            return everyWriteWithToken(4).stream();
        }

        static Stream<Step.Write> writesWithToken5() {
            return everyWriteWithToken(5).stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("writesWithTheOldToken4")
        @DisplayName("Writing with token 4 to a row claimed again with token 5 hits 0 rows")
        void anOldTokenIsFenced(Step.Write lateWrite) {
            // given
            CommandRow reclaimed = new CommandRow(StoredState.CLAIMED, 5, 1, Optional.empty(), Optional.empty(), 0);

            // when
            StepResult result = CommandStateMachine.apply(reclaimed, lateWrite);

            // then
            assertFenced(result);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("writesWithToken5")
        @DisplayName("Writing with the same token to a released row also hits 0 rows — the check covers the in-progress state, not only the token")
        void aReleasedRowIsFencedEvenWithTheSameToken(Step.Write lateWrite) {
            // given
            CommandRow released = new CommandRow(StoredState.PENDING, 5, 1, Optional.empty(), Optional.empty(), 0);

            // when
            StepResult result = CommandStateMachine.apply(released, lateWrite);

            // then
            assertFenced(result);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("writesWithToken5")
        @DisplayName("Writing to a finished row hits 0 rows — a final state does not change")
        void aFinishedRowIsFenced(Step.Write lateWrite) {
            // given
            CommandRow applied = new CommandRow(StoredState.APPLIED, 5, 1, Optional.empty(), Optional.of(Created), 0);

            // when
            StepResult result = CommandStateMachine.apply(applied, lateWrite);

            // then
            assertFenced(result);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("When the operator abandons a halted command — HALT")
    class AbandoningAHaltedCommand {

        static final CommandRow HALTED_WITH_TOKEN_1 =
                new CommandRow(StoredState.HALTED, 1, 0, Optional.empty(), Optional.empty(), 0);

        @Test
        @DisplayName("It becomes skipped and the handler runs with reason 'abandoned by operator' — the token and submit count stay the same")
        void abandoningSkipsIt() {
            // when
            StepResult result = CommandStateMachine.apply(HALTED_WITH_TOKEN_1, new Step.Abandon());

            // then
            assertMoved(result,
                    new CommandRow(StoredState.SKIPPED, 1, 0, Optional.empty(), Optional.empty(), 0,
                            Optional.of(SkipReason.ABANDONED_BY_OPERATOR)),
                    RUN_HANDLER);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"PENDING", "CLAIMED", "APPLIED", "SKIPPED"})
        @DisplayName("Abandoning a command that is not halted is refused as a bug — the service stops it first")
        void abandoningAnythingElseIsABug(StoredState notHalted) {
            // given
            CommandRow row = rowIn(notHalted, 1, 0);

            // when
            ThrowingCallable abandoning = () -> CommandStateMachine.apply(row, new Step.Abandon());

            // then
            assertBug(abandoning);
        }
    }

    @Nested
    @Order(6)
    @DisplayName("Compared with the transition table")
    class AgainstTheTable {

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.ledgerrelay.core.domain.CommandStateMachineTest$FencedWrites#writesWithToken5")
        @DisplayName("The state and effects of a write with the current token match the CLAIMED row of the table — so the state machine does not bypass the table")
        void followsTheClaimedRowOfTheTable(Step.Write write) {
            // given
            CommandRow held = new CommandRow(StoredState.CLAIMED, 5, 0, Optional.empty(), Optional.empty(), 0);
            CommandEvent event = switch (write) {
                case Step.MarkSubmitted m -> CommandEvent.SUBMITTING;
                case Step.Complete c -> CommandEvent.TB_APPLIED;
                case Step.Skip s -> CommandEvent.RESERVATION_NOT_APPLIED;
                case Step.Release r -> CommandEvent.NO_REPLY_OR_ERROR;
                case Step.HandlerFailed h -> CommandEvent.HANDLER_FAILED;
            };
            Transition tabled = TransitionTable.next(StoredState.CLAIMED, event);

            // when
            StepResult result = CommandStateMachine.apply(held, write);

            // then
            assertThat(result).isInstanceOf(StepResult.Moved.class);
            StepResult.Moved moved = (StepResult.Moved) result;
            assertThat(moved.row().state()).as("state after %s", write).isEqualTo(tabled.to());
            assertThat(moved.effects()).as("effects of %s", write).isEqualTo(tabled.effects());
        }

        /**
         * Uncovered branch 17: do the table's "impossible" and "not applicable" cells behave differently? Reaching
         * either is refused as a bug, but a not-applicable cell cannot even be reached by the state machine's inputs.
         * The code that builds events (the per-state event of claim, and writes that apply only to in-progress rows)
         * avoids those cells. The two tests in the "When a command is claimed" section check that reaching an
         * impossible cell is refused as a bug.
         */
        @Test
        @DisplayName("No input in any state reaches a 'not applicable' cell — not applicable is an explanation, not a behavior")
        void noInputReachesANotApplicableCell() {
            for (StoredState state : StoredState.values()) {
                for (Step step : everyInputWithToken(3)) {
                    // given
                    CommandRow row = rowIn(state, 3, 1);

                    // when
                    Throwable refused = catchThrowable(() -> CommandStateMachine.apply(row, step));

                    // then
                    assertThat(refused == null ? "" : refused.getMessage())
                            .as("state %s given %s", state, step).doesNotContain("NOT_APPLICABLE");
                }
            }
        }

        private static List<Step> everyInputWithToken(long token) {
            List<Step> inputs = new ArrayList<>();
            for (GateDecision gate : GateDecision.values()) {
                inputs.add(new Step.Claim(gate, false));
                inputs.add(new Step.Claim(gate, true));
            }
            inputs.add(new Step.MarkSubmitted(token));
            inputs.add(new Step.Complete(token, LedgerResultCode.Created));
            inputs.add(new Step.Skip(token));
            inputs.add(new Step.Release(token));
            inputs.add(new Step.HandlerFailed(token, Optional.of(LedgerResultCode.Created)));
            inputs.add(new Step.Abandon());
            return inputs;
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    /**
     * A row in that state. A skipped row gets the reason 'reservation not applied' (a skipped row without a reason
     * cannot be built).
     */
    private static CommandRow rowIn(StoredState state, long token, long submitCount) {
        Optional<SkipReason> why = state == StoredState.SKIPPED
                ? Optional.of(SkipReason.RESERVATION_NOT_APPLIED)
                : Optional.empty();
        return new CommandRow(state, token, submitCount, Optional.empty(), Optional.empty(), 0, why);
    }

    private static void assertMoved(StepResult actual, CommandRow expectedRow, Transition.Effect... expectedEffects) {
        assertThat(actual)
                .as("expected the row to change")
                .isEqualTo(new StepResult.Moved(expectedRow, Set.of(expectedEffects)));
    }

    private static void assertFenced(StepResult actual) {
        assertThat(actual).as("expected the write to be fenced with 0 rows").isEqualTo(new StepResult.Fenced());
    }

    private static void assertBug(ThrowingCallable step) {
        assertThatThrownBy(step).as("expected refusal as a bug")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bug");
    }
}
