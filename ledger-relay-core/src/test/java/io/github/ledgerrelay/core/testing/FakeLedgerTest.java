package io.github.ledgerrelay.core.testing;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("가짜 TB와 이벤트 기록 — 워커 테스트가 믿는 도구 (docs/testing.md §6)")
class FakeLedgerTest {

    FakeLedger ledger;

    @BeforeEach
    void givenAFakeLedger() {
        ledger = new FakeLedger();
    }

    @Nested
    @Order(1)
    @DisplayName("가짜 TB에 이체를 보내면")
    class Submitting {

        @Test
        @DisplayName("정하지 않은 이체는 기록된(Created) 것으로 끝난다")
        void unscriptedTransfersAreCreated() {
            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101), submission(102)));

            // then
            assertThat(reply).as("가짜 TB의 응답").isCompletedWithValue(List.of(Created, Created));
        }

        @Test
        @DisplayName("결과를 정해 둔 이체는 그 결과로 끝난다")
        void aScriptedTransferGetsItsAnswer() {
            // given
            ledger.answer(BigInteger.valueOf(102), ExceedsCredits);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101), submission(102)));

            // then
            assertThat(reply).as("가짜 TB의 응답").isCompletedWithValue(List.of(Created, ExceedsCredits));
        }

        @Test
        @DisplayName("응답을 붙잡으면 풀 때까지 끝나지 않는다")
        void heldRepliesDoNotEndUntilReleased() {
            // given
            ledger.holdReplies();
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101)));
            assertThat(reply).as("붙잡힌 응답").isNotDone();

            // when
            ledger.releaseHeldReplies();

            // then
            assertThat(reply).as("풀어 준 응답").isCompletedWithValue(List.of(Created));
        }

        @Test
        @DisplayName("다음 요청을 실패시키면 그 요청은 그 예외로 끝난다")
        void aFailedRequestEndsExceptionally() {
            // given
            IllegalStateException cut = new IllegalStateException("끊김");
            ledger.failNext(cut);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101)));

            // then
            assertThat(reply).as("실패시킨 요청").isCompletedExceptionally();
            assertThat(reply.exceptionNow()).isSameAs(cut);
        }

        @Test
        @DisplayName("결과 개수를 정하면 다음 요청에 그 개수만큼만 답한다")
        void aReplyCanCarryTheWrongCount() {
            // given
            ledger.replyWithCount(1);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101), submission(102)));

            // then
            assertThat(reply).as("개수를 줄인 응답").isCompletedWithValue(List.of(Created));
        }

        @Test
        @DisplayName("결과 개수를 요청보다 많게 정하면 남는 몫은 Created로 채운다")
        void aReplyCanCarryTooManyResults() {
            // given
            ledger.replyWithCount(3);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101)));

            // then
            assertThat(reply).as("개수를 늘린 응답").isCompletedWithValue(List.of(Created, Created, Created));
        }

        @Test
        @DisplayName("다음 요청을 보내는 자리에서 던지게 하면 future 없이 그 예외가 난다")
        void aSubmitCanThrowSynchronously() {
            // given
            IllegalStateException failure = new IllegalStateException("배치를 채우다 실패했다");
            ledger.throwOnNextSubmit(failure);

            // when
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> ledger.submit(List.of(submission(101))));

            // then
            assertThat(thrown).as("보내는 자리의 예외").isSameAs(failure);
        }

        @Test
        @DisplayName("받은 요청을 순서대로 남긴다 — 붙잡힌 요청도 남는다")
        void requestsAreRecordedInOrder() {
            // given
            ledger.submit(List.of(submission(101)));
            ledger.holdReplies();

            // when
            ledger.submit(List.of(submission(102), submission(103)));

            // then
            assertThat(ledger.requests()).as("받은 요청")
                    .containsExactly(List.of(submission(101)), List.of(submission(102), submission(103)));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("이벤트 기록 대역에 이벤트를 보내면")
    class RecordingEvents {

        @Test
        @DisplayName("이름과 인자를 받은 순서대로 남긴다")
        void recordsEventsWithTheirArguments() {
            // given
            RecordingWorkerEvents events = new RecordingWorkerEvents();

            // when
            events.halted("capture-1");
            events.submitted(3);
            events.ledgerUnavailable(new IllegalStateException());

            // then
            assertThat(events.events()).as("남은 이벤트")
                    .containsExactly("halted:capture-1", "submitted:3", "ledgerUnavailable");
        }
    }

    private static Submission submission(long transferId) {
        return new Submission(BigInteger.valueOf(transferId), BigInteger.ONE,
                new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                        BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }
}
