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

@DisplayName("Fake TB and event recorder — tools the worker tests rely on (docs/testing.md)")
class FakeLedgerTest {

    FakeLedger ledger;

    @BeforeEach
    void givenAFakeLedger() {
        ledger = new FakeLedger();
    }

    @Nested
    @Order(1)
    @DisplayName("When transfers are sent to the fake TB")
    class Submitting {

        @Test
        @DisplayName("A transfer with no scripted answer ends as recorded (Created)")
        void unscriptedTransfersAreCreated() {
            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101), submission(102)));

            // then
            assertThat(reply).as("fake TB reply").isCompletedWithValue(List.of(Created, Created));
        }

        @Test
        @DisplayName("A transfer with a scripted result ends with that result")
        void aScriptedTransferGetsItsAnswer() {
            // given
            ledger.answer(BigInteger.valueOf(102), ExceedsCredits);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101), submission(102)));

            // then
            assertThat(reply).as("fake TB reply").isCompletedWithValue(List.of(Created, ExceedsCredits));
        }

        @Test
        @DisplayName("A held reply does not end until it is released")
        void heldRepliesDoNotEndUntilReleased() {
            // given
            ledger.holdReplies();
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101)));
            assertThat(reply).as("held reply").isNotDone();

            // when
            ledger.releaseHeldReplies();

            // then
            assertThat(reply).as("released reply").isCompletedWithValue(List.of(Created));
        }

        @Test
        @DisplayName("When the next request is set to fail, that request ends with that exception")
        void aFailedRequestEndsExceptionally() {
            // given
            IllegalStateException cut = new IllegalStateException("cut off");
            ledger.failNext(cut);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101)));

            // then
            assertThat(reply).as("request set to fail").isCompletedExceptionally();
            assertThat(reply.exceptionNow()).isSameAs(cut);
        }

        @Test
        @DisplayName("When a result count is set, the next request gets only that many answers")
        void aReplyCanCarryTheWrongCount() {
            // given
            ledger.replyWithCount(1);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101), submission(102)));

            // then
            assertThat(reply).as("reply with fewer results").isCompletedWithValue(List.of(Created));
        }

        @Test
        @DisplayName("When the result count is set higher than the request, the extra answers are filled with Created")
        void aReplyCanCarryTooManyResults() {
            // given
            ledger.replyWithCount(3);

            // when
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(List.of(submission(101)));

            // then
            assertThat(reply).as("reply with more results").isCompletedWithValue(List.of(Created, Created, Created));
        }

        @Test
        @DisplayName("When the next submit is set to throw on the spot, the exception is thrown without a future")
        void aSubmitCanThrowSynchronously() {
            // given
            IllegalStateException failure = new IllegalStateException("failed while filling the batch");
            ledger.throwOnNextSubmit(failure);

            // when
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> ledger.submit(List.of(submission(101))));

            // then
            assertThat(thrown).as("exception at the point of sending").isSameAs(failure);
        }

        @Test
        @DisplayName("It records the requests it receives in order — held requests are recorded too")
        void requestsAreRecordedInOrder() {
            // given
            ledger.submit(List.of(submission(101)));
            ledger.holdReplies();

            // when
            ledger.submit(List.of(submission(102), submission(103)));

            // then
            assertThat(ledger.requests()).as("received requests")
                    .containsExactly(List.of(submission(101)), List.of(submission(102), submission(103)));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When events are sent to the event-recording double")
    class RecordingEvents {

        @Test
        @DisplayName("It records names and arguments in the order received")
        void recordsEventsWithTheirArguments() {
            // given
            RecordingWorkerEvents events = new RecordingWorkerEvents();

            // when
            events.halted("capture-1");
            events.submitted(3);
            events.ledgerUnavailable(new IllegalStateException());

            // then
            assertThat(events.events()).as("recorded events")
                    .containsExactly("halted:capture-1", "submitted:3", "ledgerUnavailable");
        }
    }

    private static Submission submission(long transferId) {
        return new Submission(BigInteger.valueOf(transferId), BigInteger.ONE,
                new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                        BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }
}
