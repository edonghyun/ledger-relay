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

import io.github.ledgerrelay.core.LedgerResultCode;
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

@DisplayName("토큰을 다루는 상태 기계 — 스펙 §4.3, §5.2~§5.4, G5")
class CommandStateMachineTest {

    @Nested
    @Order(1)
    @DisplayName("claim하면")
    class Claiming {

        @ParameterizedTest(name = "게이트 {0}")
        @EnumSource(value = GateDecision.class, names = {"OPEN", "SKIP"})
        @DisplayName("게이트가 열렸거나 건너뛸 대상이면 처리 중이 되고 토큰이 1 오른다")
        void anOpenOrSkippingGateClaims(GateDecision gate) {
            // when
            StepResult result = CommandStateMachine.apply(CommandRow.accepted(), new Step.Claim(gate, false));

            // then
            assertMoved(result, new CommandRow(StoredState.CLAIMED, 1, 0, Optional.empty(), Optional.empty(), 0),
                    INCREMENT_LEASE_TOKEN);
        }

        @ParameterizedTest(name = "게이트 {0}")
        @EnumSource(value = GateDecision.class, names = {"CLOSED", "HALT"})
        @DisplayName("게이트가 닫혔거나 멈췄으면 아무 일도 없다")
        void aClosedOrHaltedGateLeavesTheRow(GateDecision gate) {
            // when
            StepResult result = CommandStateMachine.apply(CommandRow.accepted(), new Step.Claim(gate, false));

            // then
            assertThat(result).as("게이트 %s에서 claim한 결과", gate).isEqualTo(new StepResult.Unchanged());
        }

        @Test
        @DisplayName("lease가 끝난 처리 중 명령은 다시 claim되고, 토큰이 올라 앞 워커의 쓰기를 막는다")
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
        @DisplayName("lease가 살아 있는 처리 중 명령을 claim하면 버그로 거부된다")
        void claimingALiveLeaseIsABug() {
            // given
            CommandRow held = new CommandRow(StoredState.CLAIMED, 3, 0, Optional.empty(), Optional.empty(), 0);

            // when
            ThrowingCallable claiming = () -> CommandStateMachine.apply(held, new Step.Claim(GateDecision.OPEN, false));

            // then
            assertBug(claiming);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("끝난 명령을 claim하면 버그로 거부된다")
        void claimingAFinishedCommandIsABug(StoredState finished) {
            // given
            CommandRow done = new CommandRow(finished, 2, 1, Optional.empty(), Optional.empty(), 0);

            // when
            ThrowingCallable claiming = () -> CommandStateMachine.apply(done, new Step.Claim(GateDecision.OPEN, false));

            // then
            assertBug(claiming);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("현재 토큰으로 쓰면")
    class WritingWithTheCurrentToken {

        static final CommandRow HELD_WITH_TOKEN_5 =
                new CommandRow(StoredState.CLAIMED, 5, 0, Optional.empty(), Optional.empty(), 0);

        @Test
        @DisplayName("제출을 기록하면 처리 중 그대로이고 제출 횟수가 1 오른다")
        void recordingASubmissionCountsIt() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.MarkSubmitted(5));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.CLAIMED, 5, 1, Optional.empty(), Optional.empty(), 0),
                    RECORD_SUBMISSION);
        }

        @Test
        @DisplayName("성공(Created)으로 끝내면 적용됨이 되고, 결과 코드가 남고, 핸들러가 실행된다")
        void completingWithSuccessApplies() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Complete(5, Created));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.APPLIED, 5, 0, Optional.empty(), Optional.of(Created), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("잔액 부족(ExceedsCredits)으로 끝내면 거절됨이 된다")
        void completingWithABusinessRejectionRejects() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Complete(5, ExceedsCredits));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.REJECTED, 5, 0, Optional.empty(), Optional.of(ExceedsCredits), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("내용 충돌로 끝내면 충돌이 된다")
        void completingWithAConflictConflicts() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Complete(5, ExistsWithDifferentAmount));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.CONFLICT, 5, 0, Optional.empty(), Optional.of(ExistsWithDifferentAmount), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("건너뛰면 건너뜀이 되고, 결과 코드 없이 핸들러가 실행된다")
        void skippingSkips() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_WITH_TOKEN_5, new Step.Skip(5));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.SKIPPED, 5, 0, Optional.empty(), Optional.empty(), 0),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("응답이 없어 되돌리면 접수 상태로 돌아가 backoff를 기다리고, 토큰과 제출 횟수는 그대로다")
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
        @DisplayName("핸들러가 실패하면 접수 상태로 돌아가고, 받은 TB 결과를 남기고, 실패를 센다")
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
    @DisplayName("이미 아는 TB 결과가 있으면 — §5.4")
    class WithAKnownResult {

        static final CommandRow HELD_KNOWING_EXCEEDS_CREDITS =
                new CommandRow(StoredState.CLAIMED, 6, 1, Optional.of(ExceedsCredits), Optional.empty(), 1);

        @Test
        @DisplayName("그 결과로 끝내면 거절됨이 되고 핸들러가 다시 실행된다 — TB에는 다시 보내지 않는다")
        void completesWithTheKnownResult() {
            // when
            StepResult result = CommandStateMachine.apply(HELD_KNOWING_EXCEEDS_CREDITS, new Step.Complete(6, ExceedsCredits));

            // then
            assertMoved(result,
                    new CommandRow(StoredState.REJECTED, 6, 1, Optional.of(ExceedsCredits), Optional.of(ExceedsCredits), 1),
                    RUN_HANDLER);
        }

        @Test
        @DisplayName("TB 제출을 기록하면 버그로 거부된다 — 다시 보내면 원래 사유가 IdAlreadyFailed로 덮인다")
        void submittingAgainIsABug() {
            // when
            ThrowingCallable writing = () -> CommandStateMachine.apply(HELD_KNOWING_EXCEEDS_CREDITS, new Step.MarkSubmitted(6));

            // then
            assertBug(writing);
        }

        @Test
        @DisplayName("다른 코드로 끝내면 버그로 거부된다")
        void completingWithAnotherCodeIsABug() {
            // when
            ThrowingCallable writing = () -> CommandStateMachine.apply(HELD_KNOWING_EXCEEDS_CREDITS, new Step.Complete(6, IdAlreadyFailed));

            // then
            assertBug(writing);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("오래된 토큰이거나 처리 중이 아닌 행에 쓰면 — G5, E3")
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
        @DisplayName("토큰 5로 다시 claim된 행에 토큰 4로 쓰면 0행이다")
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
        @DisplayName("되돌려진 행에 같은 토큰으로 써도 0행이다 — 토큰만이 아니라 처리 중 상태도 본다")
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
        @DisplayName("끝난 행에 쓰면 0행이다 — 최종 상태는 바뀌지 않는다")
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
    @DisplayName("전이표와 견주면")
    class AgainstTheTable {

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.ledgerrelay.core.domain.CommandStateMachineTest$FencedWrites#writesWithToken5")
        @DisplayName("현재 토큰으로 쓴 결과의 상태와 효과는 전이표의 CLAIMED 줄과 같다 — 상태 기계가 표를 우회하지 않게")
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
            assertThat(moved.row().state()).as("%s 뒤의 상태", write).isEqualTo(tabled.to());
            assertThat(moved.effects()).as("%s의 효과", write).isEqualTo(tabled.effects());
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static void assertMoved(StepResult actual, CommandRow expectedRow, Transition.Effect... expectedEffects) {
        assertThat(actual)
                .as("행이 바뀌기를 기대했다")
                .isEqualTo(new StepResult.Moved(expectedRow, Set.of(expectedEffects)));
    }

    private static void assertFenced(StepResult actual) {
        assertThat(actual).as("쓰기가 0행으로 막히기를 기대했다").isEqualTo(new StepResult.Fenced());
    }

    private static void assertBug(ThrowingCallable step) {
        assertThatThrownBy(step).as("버그로 거부되기를 기대했다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("버그");
    }
}
