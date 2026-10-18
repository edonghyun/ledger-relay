package io.github.ledgerrelay.core.application;

import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.FakeLedger;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.RecordingWorkerEvents;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("One worker cycle — submission and transitions")
class RelayWorkerTest {

    static final Duration BACKOFF_MAX = Duration.ofSeconds(1);
    static final WorkerSettings SETTINGS =
            new WorkerSettings(128, 1024, Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(100));

    InMemoryStorage storage;
    RecordingHandler handler;
    CommandLifecycle relay;
    FakeLedger fake;
    InFlightLimit slots;
    RecordingWorkerEvents events;
    RelayWorker worker;

    @BeforeEach
    void givenAWorkerOnAFakeLedger() {
        storage = InMemoryStorage.create();
        handler = new RecordingHandler();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
        fake = new FakeLedger();
        slots = new InFlightLimit(SETTINGS.maxInFlight());
        events = new RecordingWorkerEvents();
        worker = new RelayWorker(relay, fake, slots, SETTINGS, events);
    }

    @Nested
    @Order(1)
    @DisplayName("When a cycle runs with accepted transfers")
    class WithAcceptedTransfers {

        @Test
        @DisplayName("It sends them to TB, ends them applied as TB answered, and runs the handler once")
        void aTransferIsSubmittedAndApplied() {
            // given
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertThat(handler.completedFor("pay-1")).as("handler runs for pay-1").hasSize(1);
            assertThat(report).as("cycle report").isEqualTo(new CycleReport(1, 1, 1, 0));
        }

        @Test
        @DisplayName("Commands of different keys go into one TB request in acceptance order")
        void allSubmissionsGoInOneRequestInClaimOrder() {
            // given
            BigInteger first = accepted(transfer("pay-1", "order-1")).transferId();
            BigInteger second = accepted(transfer("pay-2", "order-2")).transferId();
            BigInteger third = accepted(transfer("pay-3", "order-3")).transferId();

            // when
            worker.runOnce();

            // then
            assertRequests(List.of(first, second, third));
        }

        @Test
        @DisplayName("When TB rejects for insufficient funds, the command ends rejected and keeps that code")
        void aRejectionEndsTheCommandRejected() {
            // given
            fake.answer(accepted(transfer("pay-1", "order-1")).transferId(), ExceedsCredits);

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.REJECTED);
            assertThat(relay.status("pay-1").orElseThrow().rejection().orElseThrow().tbCode())
                    .as("rejection code of pay-1").isEqualTo(ExceedsCredits);
        }

        @Test
        @DisplayName("Of the commands on one key only the head goes into a request, and the next one goes in the cycle after it ends (G4)")
        void onlyTheHeadOfAKeyGoesInARequest() {
            // given
            BigInteger first = accepted(transfer("pay-1", "order-1")).transferId();
            BigInteger second = accepted(transfer("pay-2", "order-1")).transferId();

            // when
            worker.runOnce();
            worker.runOnce();

            // then
            assertRequests(List.of(first), List.of(second));
        }

        @Test
        @DisplayName("The sent transfer carries the command's row id in user_data_128 — TB refers back to PG")
        void theSubmissionCarriesTheRowIdAsUserData128() {
            // given
            accepted(transfer("pay-1", "order-1"));
            long rowId = storage.transactions().inTransaction(() -> storage.store().find("pay-1")).orElseThrow().rowId();

            // when
            worker.runOnce();

            // then
            assertThat(fake.requests().get(0).get(0).userData128()).as("user_data_128 of the sent transfer")
                    .isEqualTo(BigInteger.valueOf(rowId));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When some commands end without being sent to TB")
    class WithoutARequest {

        @Test
        @DisplayName("A post that refers to a rejected reservation ends skipped without a TB request")
        void aPostOfARejectedReservationIsSkippedWithoutARequest() {
            // given: hold-1 was rejected for insufficient funds
            fake.answer(accepted(reserve("hold-1", "order-1")).transferId(), ExceedsCredits);
            worker.runOnce();
            accepted(LedgerCommand.post("capture-1").orderingKey("order-1").reservation("hold-1").build());

            // when
            worker.runOnce();

            // then
            assertState("capture-1", State.SKIPPED);
            assertThat(fake.requests()).as("TB requests (only the one for hold-1)").hasSize(1);
        }

        @Test
        @DisplayName("A command whose result is known because the handler failed ends with that result without a TB request")
        void aKnownResultCompletesWithoutARequest() {
            // given: in the first cycle TB recorded it but the handler failed
            handler.failNextCalls(1);
            accepted(transfer("pay-1", "order-1"));
            worker.runOnce();
            storage.elapse(BACKOFF_MAX);

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertThat(fake.requests()).as("TB requests (only the one from the first cycle)").hasSize(1);
        }

        @Test
        @DisplayName("A post sequenced before its reservation is reported once as halted, and not reported again in the next cycle")
        void aHaltedPostIsReportedOnce() {
            // given: this order cannot get past the acceptance checks, so the rows go straight into the store (same
            // values as LedgerRelayContract)
            insertedPastTheChecks(new NewCommand("hold-1", "order-1", 2, Optional.empty(), BigInteger.valueOf(102),
                    "hash-of-hold-1", new TransferBody(CommandKind.RESERVE, BigInteger.ONE, BigInteger.TWO,
                            BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0)));
            insertedPastTheChecks(new NewCommand("capture-1", "order-1", 1, Optional.of("hold-1"),
                    BigInteger.valueOf(101), "hash-of-capture-1", new TransferBody(CommandKind.POST, BigInteger.ZERO,
                            BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0, Optional.of(BigInteger.valueOf(102)), 0, 0)));

            // when
            worker.runOnce();
            worker.runOnce();

            // then
            assertThat(events.events()).as("worker events").containsOnlyOnce("halted:capture-1");
            assertThat(fake.requests()).as("TB requests").isEmpty();
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When TB does not answer within submit-timeout")
    class WhenTheLedgerDoesNotAnswer {

        @BeforeEach
        void givenRepliesAreHeld() {
            fake.holdReplies();
        }

        @Test
        @DisplayName("The commands are released to be sent again later, and the query still shows them in progress")
        void theCommandsAreReleasedForALaterRetry() {
            // given
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("released commands").isEqualTo(1);
            assertState("pay-1", State.IN_PROGRESS);
            assertThat(events.events()).as("worker events").contains("submitTimedOut:1");
        }

        @Test
        @DisplayName("The slots of sent transfers do not come back until the request actually ends — the request is still inside the TB client")
        void aTimedOutRequestKeepsItsSlotsUntilItEnds() {
            // given: a worker with 4 slots and batch 4, and two transfers on different keys
            InFlightLimit four = new InFlightLimit(4);
            RelayWorker small = new RelayWorker(relay, fake, four,
                    new WorkerSettings(4, 4, Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(100)),
                    events);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));
            small.runOnce();
            assertThat(four.available()).as("remaining slots right after the timeout").isEqualTo(2);

            // when
            fake.releaseHeldReplies();

            // then
            assertThat(four.available()).as("remaining slots after the request ended").isEqualTo(4);
        }

        @Test
        @DisplayName("A reply that arrives late after the timeout writes nothing — the handler does not run either")
        void aLateReplyAfterTheTimeoutWritesNothing() {
            // given
            accepted(transfer("pay-1", "order-1"));
            worker.runOnce();

            // when
            fake.releaseHeldReplies();

            // then
            assertThat(handler.completedFor("pay-1")).as("handler runs for pay-1").isEmpty();
            assertState("pay-1", State.IN_PROGRESS);
            assertThat(events.events()).as("write attempt for the late reply (it would have been fenced with 0 rows)").doesNotContain("fenced:pay-1");
        }

        @Test
        @DisplayName("The resend after backoff uses the same transfer id, and the command ends with that result")
        void theRetryUsesTheSameTransferId() {
            // given: the first request was released on timeout, and now TB answers
            BigInteger transferId = accepted(transfer("pay-1", "order-1")).transferId();
            worker.runOnce();
            fake.releaseHeldReplies();
            storage.elapse(BACKOFF_MAX);

            // when
            worker.runOnce();

            // then
            assertRequests(List.of(transferId), List.of(transferId));
            assertState("pay-1", State.APPLIED);
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When the TB request ends with an exception or the result count is wrong")
    class WhenTheRequestFailsOrTheReplyIsMalformed {

        @Test
        @DisplayName("When it ends with an exception, the commands are released and every slot comes back")
        void aFailedRequestReleasesItsCommands() {
            // given
            fake.failNext(new IllegalStateException("the connection dropped"));
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("released commands").isEqualTo(1);
            assertThat(events.events()).as("worker events").contains("submitFailed:1");
            assertThat(slots.available()).as("remaining slots").isEqualTo(SETTINGS.maxInFlight());
        }

        @Test
        @DisplayName("When the result count differs from the request, nothing is written and all are released — results are paired by position, so no command ends with another's result")
        void aReplyWithTheWrongCountWritesNothing() {
            // given
            fake.replyWithCount(1);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));

            // when
            worker.runOnce();

            // then
            assertThat(handler.completedFor("pay-1")).as("handler runs for pay-1").isEmpty();
            assertState("pay-1", State.IN_PROGRESS);
            assertState("pay-2", State.IN_PROGRESS);
            assertThat(events.events()).as("worker events").contains("malformedReply:2:1");
        }

        @Test
        @DisplayName("A reply with the wrong result count releases every sent command — none is left in progress")
        void aMalformedReplyReleasesEveryCommand() {
            // given
            fake.replyWithCount(1);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("released commands").isEqualTo(2);
        }

        @Test
        @DisplayName("Nothing is written when there are more results than requests either")
        void aReplyWithTooManyResultsWritesNothing() {
            // given
            fake.replyWithCount(3);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));

            // when
            worker.runOnce();

            // then
            assertThat(handler.completedFor("pay-1")).as("handler runs for pay-1").isEmpty();
            assertThat(events.events()).as("worker events").contains("malformedReply:2:3");
        }

        @Test
        @DisplayName("When sending the request throws on the spot, the commands are released and every slot comes back")
        void aSynchronousSubmitFailureReleasesAndReturnsSlots() {
            // given
            fake.throwOnNextSubmit(new IllegalStateException("failed while filling the batch"));
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("released commands").isEqualTo(1);
            assertThat(slots.available()).as("remaining slots").isEqualTo(1024);
            assertThat(events.events()).as("worker events").contains("submitFailed:1");
        }

        @Test
        @DisplayName("A failure saying the TB client can no longer be used is reported apart from transient failures — so the operator knows a restart is needed")
        void anUnavailableLedgerIsReportedDistinctly() {
            // given
            fake.failNext(new LedgerUnavailableException("the client was closed", new IllegalStateException()));
            accepted(transfer("pay-1", "order-1"));

            // when
            worker.runOnce();

            // then
            assertThat(events.events()).as("worker events").contains("ledgerUnavailable").doesNotContain("submitFailed:1");
        }
    }

    @Nested
    @Order(7)
    @DisplayName("When writing a result hits 0 rows or the handler fails")
    class WhenAResultWriteDoesNotLand {

        @Test
        @DisplayName("When the handler fails, the command is reported and not counted as completed")
        void aHandlerFailureIsReported() {
            // given
            handler.failNextCalls(1);
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(events.events()).as("worker events").contains("handlerFailed:pay-1");
            assertThat(report.completed()).as("commands counted as completed").isZero();
        }

        @Test
        @DisplayName("When another worker took the command while the reply was awaited, the 0-row write is reported and not counted as completed")
        void aFencedWriteIsReported() {
            // given: while it was being sent to TB, the lease ran out and another worker claimed the same command
            accepted(transfer("pay-1", "order-1"));
            LedgerClient overtakingLedger = new LedgerClient() {
                @Override
                public java.util.concurrent.CompletableFuture<List<io.github.ledgerrelay.core.LedgerResultCode>> submit(
                        List<io.github.ledgerrelay.core.spi.Submission> batch) {
                    storage.elapse(SETTINGS.lease().plusSeconds(1));
                    relay.claim(10, SETTINGS.lease());
                    return fake.submit(batch);
                }

                @Override
                public java.util.concurrent.CompletableFuture<List<io.github.ledgerrelay.core.spi.LedgerTransfer>> lookup(
                        List<java.math.BigInteger> transferIds) {
                    return fake.lookup(transferIds);
                }
            };
            RelayWorker overtaken = new RelayWorker(relay, overtakingLedger, slots, SETTINGS, events);

            // when
            CycleReport report = overtaken.runOnce();

            // then
            assertThat(events.events()).as("worker events").contains("fenced:pay-1");
            assertThat(report.completed()).as("commands counted as completed").isZero();
        }
    }

    @Nested
    @Order(5)
    @DisplayName("When in-flight slots run out")
    class WhenSlotsRunOut {

        @Test
        @DisplayName("It rests without claiming — the command stays accepted and no lease is taken (backpressure)")
        void noSlotsMeansNoClaim() {
            // given: another side holds every slot
            slots.tryTake(SETTINGS.maxInFlight(), Duration.ZERO);
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report).as("cycle report").isEqualTo(CycleReport.IDLE);
            assertState("pay-1", State.ACCEPTED);
            assertThat(storage.transactions().inTransaction(() -> storage.store().find("pay-1")).orElseThrow()
                    .row().leaseToken()).as("lease token of pay-1").isZero();
            assertThat(events.events()).as("worker events").contains("noSlots");
        }

        @Test
        @DisplayName("Slots for the unsent part come back at once — with one command, 127 of the 128 batch slots come back")
        void unusedSlotsAreGivenBackAtOnce() {
            // given
            fake.holdReplies();
            accepted(transfer("pay-1", "order-1"));

            // when
            worker.runOnce();

            // then
            assertThat(slots.available()).as("remaining slots while one held request keeps its slot").isEqualTo(SETTINGS.maxInFlight() - 1);
        }
    }

    @Nested
    @Order(6)
    @DisplayName("When the store fails on one command while results are written")
    class WhenAWriteFails {

        @Test
        @DisplayName("Only that command is reported and the other results are still written — so a rejection reason already received is not overwritten by IdAlreadyFailed")
        void theOtherResultsOfTheBatchAreStillWritten() {
            // given: the store fails once when the result of pay-1 is written (the lookup while recording the
            // submission goes through)
            FlakyStore flaky = new FlakyStore(storage.store(), "pay-1", 1);
            CommandLifecycle onFlaky = new CommandLifecycle(storage.keys(), flaky, storage.transactions(), new SequentialTransferIds(),
                    handler, new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
            RelayWorker flakyWorker = new RelayWorker(onFlaky, fake, slots, SETTINGS, events);
            storage.transactions().inTransaction(() -> onFlaky.submit(transfer("pay-1", "order-1")));
            fake.answer(storage.transactions().inTransaction(() -> onFlaky.submit(transfer("pay-2", "order-2")))
                    .transferId(), ExceedsCredits);
            flaky.arm();

            // when
            flakyWorker.runOnce();

            // then
            assertThat(onFlaky.status("pay-2").orElseThrow().rejection().orElseThrow().tbCode())
                    .as("rejection code of pay-2").isEqualTo(ExceedsCredits);
            assertThat(events.events()).as("worker events").contains("writeFailed:pay-1");
            assertThat(fake.requests().get(0)).as("request sent to TB (the failure happened while writing the result)").hasSize(2);
        }

        @Test
        @DisplayName("A command whose submission could not be recorded is not sent to TB — so no unrecorded submission is left in TB")
        void aCommandWhoseSubmissionWasNotRecordedIsNotSent() {
            // given: the store fails once when the submission of pay-1 is recorded
            FlakyStore flaky = new FlakyStore(storage.store(), "pay-1", 0);
            CommandLifecycle onFlaky = new CommandLifecycle(storage.keys(), flaky, storage.transactions(), new SequentialTransferIds(),
                    handler, new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
            RelayWorker flakyWorker = new RelayWorker(onFlaky, fake, slots, SETTINGS, events);
            storage.transactions().inTransaction(() -> onFlaky.submit(transfer("pay-1", "order-1")));
            flaky.arm();

            // when
            flakyWorker.runOnce();

            // then
            assertThat(fake.requests()).as("TB requests").isEmpty();
            assertThat(events.events()).as("worker events").contains("writeFailed:pay-1");
        }
    }

    @Nested
    @Order(10)
    @DisplayName("When the handler throws and recording that failure fails too")
    class WhenTheHandlerFailureCannotBeRecorded {

        @Test
        @DisplayName("The store exception carries the handler exception — so the operator does not lose the original cause")
        void theHandlersFailureSurvivesAFailedRecord() {
            // given: the handler throws once, and the store fails once on the lookup that records that failure
            //        (lookups of pay-1: 1 recording the submission, 2 writing the result, 3 the status for the handler,
            //        4 recording the failure)
            FlakyStore flaky = new FlakyStore(storage.store(), "pay-1", 3);
            CommandLifecycle onFlaky = new CommandLifecycle(storage.keys(), flaky, storage.transactions(),
                    new SequentialTransferIds(), handler, new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
            RelayWorker flakyWorker = new RelayWorker(onFlaky, fake, slots, SETTINGS, events);
            storage.transactions().inTransaction(() -> onFlaky.submit(transfer("pay-1", "order-1")));
            handler.failNextCalls(1);
            flaky.arm();

            // when
            flakyWorker.runOnce();

            // then
            RuntimeException recorded = events.writeFailure("pay-1");
            assertThat(recorded).as("reported write failure").hasMessage("the DB connection dropped briefly");
            assertThat(recorded.getSuppressed()).as("attached exceptions")
                    .anySatisfy(e -> assertThat(e).isInstanceOf(RecordingHandler.HandlerFailure.class));
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    /**
     * A store that, after {@link #arm()}, skips this commandId's lookups skip times and then throws on the next one.
     * Everything else passes through. Arm it after acceptance so the lookups that acceptance makes are not counted.
     */
    private static final class FlakyStore implements CommandStore {

        private final CommandStore real;
        private final String commandId;
        private int skip;
        private boolean failed;
        private boolean armed;

        void arm() {
            armed = true;
        }

        FlakyStore(CommandStore real, String commandId, int skip) {
            this.real = real;
            this.commandId = commandId;
            this.skip = skip;
        }


        @Override
        public Optional<CommandRecord> insertIfAbsent(NewCommand command) {
            return real.insertIfAbsent(command);
        }

        @Override
        public Optional<CommandRecord> find(String id) {
            if (armed && id.equals(commandId) && !failed && skip-- <= 0) {
                failed = true;
                throw new IllegalStateException("the DB connection dropped briefly");
            }
            return real.find(id);
        }

        @Override
        public io.github.ledgerrelay.core.spi.Backlog backlog() {
            return real.backlog();
        }

        @Override
        public List<CommandRecord> findFinished(long afterRowId, int limit) {
            return real.findFinished(afterRowId, limit);
        }

        @Override
        public List<CommandRecord> findHalted(long afterRowId, int limit) {
            return real.findHalted(afterRowId, limit);
        }

        @Override
        public List<DueCommand> lockDue(int limit) {
            return real.lockDue(limit);
        }

        @Override
        public boolean compareAndSet(RowChange change) {
            return real.compareAndSet(change);
        }
    }

    private CommandReceipt accepted(LedgerCommand command) {
        return storage.transactions().inTransaction(() -> relay.submit(command));
    }

    @Nested
    @Order(8)
    @DisplayName("When the slots disagree with the settings")
    class WhenSlotsDisagreeWithSettings {

        @Test
        @DisplayName("The worker cannot be built — so it does not run with a max-in-flight setting that is not the real limit")
        void aWorkerRefusesSlotsThatDisagreeWithMaxInFlight() {
            // when
            Throwable refused = org.assertj.core.api.Assertions.catchThrowable(
                    () -> new RelayWorker(relay, fake, new InFlightLimit(512), SETTINGS, events));

            // then
            assertThat(refused).as("refusal").isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("512").hasMessageContaining("1024");
        }
    }

    @Nested
    @Order(9)
    @DisplayName("When the TB client returns null instead of a future")
    class WhenTheClientReturnsNull {

        @Test
        @DisplayName("It is treated as a failed request, the commands are released, and every slot comes back")
        void aNullReplyGivesTheSlotsBackAndReleasesTheBatch() {
            // given
            fake.replyWithNull();
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("released commands").isEqualTo(1);
            assertThat(slots.available()).as("remaining slots").isEqualTo(1024);
            assertThat(events.events()).as("worker events").contains("submitFailed:1");
        }
    }

    private void insertedPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given setup failed: " + row.commandId() + " already existed");
        }
    }

    private static LedgerCommand transfer(String commandId, String orderingKey) {
        return LedgerCommand.transfer(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }

    private static LedgerCommand reserve(String commandId, String orderingKey) {
        return LedgerCommand.reserve(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(500).ledger(1).code(1).timeoutSeconds(30).build();
    }

    private void assertState(String commandId, State expected) {
        assertThat(relay.status(commandId).orElseThrow().state()).as("query state of %s", commandId).isEqualTo(expected);
    }

    /** Looks at the transfer ids inside each request TB received, in order. */
    @SafeVarargs
    private void assertRequests(List<BigInteger>... expected) {
        List<List<BigInteger>> actual = fake.requests().stream()
                .map(request -> request.stream().map(Submission::transferId).toList())
                .toList();
        assertThat(actual).as("requests TB received (transfer ids)").containsExactly(expected);
    }
}
