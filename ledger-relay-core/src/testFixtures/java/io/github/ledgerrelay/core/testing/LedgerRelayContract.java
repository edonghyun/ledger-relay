package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.CannotAbandonException;
import io.github.ledgerrelay.core.CommandNotFoundException;
import io.github.ledgerrelay.core.HaltReason;
import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static io.github.ledgerrelay.core.LedgerResultCode.ExistsWithDifferentAmount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.InvalidCommandException;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.Rejection;
import io.github.ledgerrelay.core.ReservationNotFoundException;
import io.github.ledgerrelay.core.ResultCategory;
import io.github.ledgerrelay.core.SkipReason;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimBatch;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.WriteResult;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * The behavior contract of the {@link CommandLifecycle} service (G1, G4~G7). It must hold on top of any store.
 * Each implementation has one test that extends this class. The in-memory store (core) and the PostgreSQL store (jdbc)
 * run the same sections.
 */
public abstract class LedgerRelayContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration BACKOFF = Duration.ofSeconds(5);

    protected final RecordingHandler handler = new RecordingHandler();
    protected StorageUnderTest storage;
    protected CommandLifecycle relay;

    /** Builds one empty set of storage for each test. */
    protected abstract StorageUnderTest createStorage();

    @BeforeEach
    protected void givenAnEmptyRelay() {
        storage = createStorage();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(BACKOFF, BACKOFF.multipliedBy(4)));
    }

    @Nested
    @Order(1)
    @DisplayName("When a command is submitted")
    class Accepting {

        @Test
        @DisplayName("It reads as accepted and carries the TB transfer id from the generator")
        void aNewCommandIsAccepted() {
            // when
            CommandReceipt receipt = submit(transfer("pay-1", "order-1", 500));

            // then
            assertThat(receipt).isEqualTo(new CommandReceipt("pay-1", BigInteger.ONE));
            assertQueryState("pay-1", State.ACCEPTED);
            assertThat(status("pay-1").transferId()).as("transfer id from the query").isEqualTo(BigInteger.ONE);
        }

        @Test
        @DisplayName("Submitting the same command again returns the first receipt unchanged — the transfer id does not change")
        void acceptingTheSameCommandAgainReturnsTheFirstReceipt() {
            // given
            CommandReceipt first = submit(transfer("pay-1", "order-1", 500));

            // when
            CommandReceipt again = submit(transfer("pay-1", "order-1", 500));

            // then
            assertThat(again).isEqualTo(first);
        }

        @Test
        @DisplayName("Querying a commandId that was never accepted returns nothing")
        void anUnknownCommandIsNotFound() {
            // when
            Optional<CommandStatus> found = relay.status("never-accepted");

            // then
            assertThat(found).as("query result for a command never accepted").isEmpty();
        }

        @Test
        @DisplayName("Querying a commandId that contains U+0000 returns nothing, and the same app transaction stays usable (F20)")
        void aMalformedIdIsNotFoundAndTheAppTransactionStaysUsable() {
            // when: inside the app transaction, query with an external key and then submit
            Optional<CommandStatus> found = storage.transactions().inTransaction(() -> {
                Optional<CommandStatus> looked = relay.status("pay\u00001");
                relay.submit(transfer("pay-2", "order-1", 500));
                return looked;
            });

            // then
            assertThat(found).as("query result for a commandId with U+0000").isEmpty();
            assertThat(relay.status("pay-2")).as("command submitted next in the same transaction").isPresent();
        }

        @Test
        @DisplayName("Querying a commandId that contains a lone surrogate does not return the different command whose id has '?' (F20)")
        void aLoneSurrogateDoesNotMatchAnotherCommand() {
            // given
            submit(transfer("pay-?", "order-1", 500));

            // when
            Optional<CommandStatus> found = relay.status("pay-\uD800");

            // then
            assertThat(found).as("query result for a commandId with a lone surrogate").isEmpty();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("Given an accepted transfer of 500, when different content is submitted under the same commandId — G7")
    class AcceptingOtherContentUnderTheSameId {

        CommandReceipt first;

        @BeforeEach
        void givenPay1Of500IsAccepted() {
            first = submit(transfer("pay-1", "order-1", 500));
        }

        @Test
        @DisplayName("Changing only the amount is refused as a content conflict, and the first content still gets the same receipt")
        void aDifferentAmountIsAConflict() {
            // when
            ThrowingCallable acceptingAnotherAmount = () -> submit(transfer("pay-1", "order-1", 501));

            // then
            assertRejectedAsConflict(acceptingAnotherAmount, "pay-1");
            assertThat(submit(transfer("pay-1", "order-1", 500)))
                    .as("receipt for resubmitting the first content").isEqualTo(first);
        }

        @Test
        @DisplayName("Changing only the ordering key is also refused as a content conflict")
        void aDifferentOrderingKeyIsAConflict() {
            // when
            ThrowingCallable acceptingUnderAnotherKey = () -> submit(transfer("pay-1", "order-2", 500));

            // then
            assertRejectedAsConflict(acceptingUnderAnotherKey, "pay-1");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When a post or void that refers to a reservation is submitted")
    class AcceptingAReference {

        @BeforeEach
        void givenAReservationInOrder1() {
            submit(reserve("hold-1", "order-1", 500));
        }

        @Test
        @DisplayName("A post that refers to a reservation accepted under the same key is accepted")
        void aPostOfAnAcceptedReservationIsAccepted() {
            // when
            submit(post("capture-1", "order-1", "hold-1"));

            // then
            assertQueryState("capture-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("A void that refers to a reservation accepted under the same key is accepted")
        void aVoidOfAnAcceptedReservationIsAccepted() {
            // when
            submit(voidOf("release-1", "order-1", "hold-1"));

            // then
            assertQueryState("release-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("Referring to a reservation that was never accepted is refused, and no post remains")
        void aMissingReservationIsRefused() {
            // when
            ThrowingCallable referencingHold9 = () -> submit(post("capture-1", "order-1", "hold-9"));

            // then
            assertRejectedAsMissingReservation(referencingHold9, "capture-1");
            assertThat(relay.status("capture-1")).as("refused post").isEmpty();
        }

        @Test
        @DisplayName("Referring to a reservation under a different ordering key is refused")
        void aReservationUnderAnotherKeyIsRefused() {
            // when
            ThrowingCallable referencingFromOrder2 = () -> submit(post("capture-1", "order-2", "hold-1"));

            // then
            assertRejectedAsMissingReservation(referencingFromOrder2, "capture-1");
        }

        @Test
        @DisplayName("Referring to a transfer that is not a reservation is refused")
        void aPlainTransferIsNotAReservation() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ThrowingCallable referencingATransfer = () -> submit(post("capture-1", "order-1", "pay-1"));

            // then
            assertRejectedAsMissingReservation(referencingATransfer, "capture-1");
        }

        @Test
        @DisplayName("Referring to itself makes the command impossible to build, so it never reaches acceptance")
        void aCommandCannotReferenceItself() {
            // when
            ThrowingCallable referencingItself = () -> submit(post("capture-1", "order-1", "capture-1"));

            // then
            assertThatThrownBy(referencingItself)
                    .as("post referring to itself is not built (InvalidCommandException)")
                    .isInstanceOf(InvalidCommandException.class)
                    .hasMessageContaining("cannot refer to themselves as the reservation");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When acceptance is bound to the app transaction — G1")
    class WithTheAppTransaction {

        @Test
        @DisplayName("Submitting outside a transaction is refused, and nothing remains")
        void submittingOutsideATransactionIsRefused() {
            // when
            ThrowingCallable submittingOutside = () -> relay.submit(transfer("pay-1", "order-1", 500));

            // then
            assertThatThrownBy(submittingOutside)
                    .as("expected a refusal outside a transaction")
                    .isInstanceOf(storage.outsideTransactionError());
            assertThat(relay.status("pay-1")).as("refused command").isEmpty();
        }

        @Test
        @DisplayName("When the app transaction rolls back, the acceptance disappears too")
        void aRolledBackAppTransactionTakesTheCommandWithIt() {
            // when
            Runnable submitThenFail = () -> storage.transactions().inTransaction(() -> {
                relay.submit(transfer("pay-1", "order-1", 500));
                throw new AppFailure();
            });

            // then
            assertThatThrownBy(submitThenFail::run).isInstanceOf(AppFailure.class);
            assertThat(relay.status("pay-1")).as("rolled-back command").isEmpty();
        }

        @Test
        @DisplayName("Inside the same transaction, the command just submitted can be queried")
        void theSameTransactionSeesItsOwnCommand() {
            // when
            State seen = storage.transactions().inTransaction(() -> {
                relay.submit(transfer("pay-1", "order-1", 500));
                return relay.status("pay-1").map(CommandStatus::state).orElse(null);
            });

            // then
            assertThat(seen).as("state seen in the same transaction").isEqualTo(State.ACCEPTED);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("When a command is claimed — G4")
    class Claiming {

        @Test
        @DisplayName("An accepted command is claimed with token 1 and still reads as accepted, because it has not been sent to TB")
        void anAcceptedCommandIsClaimedWithToken1() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-1");
            ClaimedCommand claimed = batch.claimed().get(0);
            assertThat(claimed.leaseToken()).as("token of the first claim").isEqualTo(1);
            assertThat(claimed.skip()).as("whether it is to be skipped").isFalse();
            assertThat(claimed.knownResult()).as("known result").isEmpty();
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("A claimed command carries the transfer content to send to TB — transfer id, accounts, amount, and the row id for back-reference")
        void aClaimedCommandCarriesItsTransfer() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ClaimedCommand claimed = claimOnly("pay-1");

            // then
            assertThat(claimed.submission()).isEqualTo(new Submission(BigInteger.ONE,
                    BigInteger.valueOf(claimed.rowId()),
                    new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                            BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0)));
        }

        @Test
        @DisplayName("A later command is not claimed while an earlier command of the same key is unfinished")
        void aLaterCommandWaitsForTheEarlierOne() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            submit(transfer("pay-2", "order-1", 300));
            claimOnly("pay-1");

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("Commands of different ordering keys do not wait for each other")
        void differentKeysDoNotWaitForEachOther() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            submit(transfer("pay-2", "order-2", 300));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-1", "pay-2");
        }

        @Test
        @DisplayName("A command is not claimed again while its lease is live")
        void aLiveLeaseIsNotClaimedAgain() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            claimOnly("pay-1");
            storage.elapse(LEASE.dividedBy(2));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("When the lease ends, another worker claims it again and the token goes up to 2")
        void anExpiredLeaseIsClaimedAgainWithANewToken() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            claimOnly("pay-1");
            storage.elapse(LEASE.plusSeconds(1));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-1");
            assertThat(batch.claimed().get(0).leaseToken()).as("token of the repeated claim").isEqualTo(2);
        }

        @Test
        @DisplayName("At most limit commands are claimed at a time — in acceptance order when the times are equal")
        void claimsAtMostLimitInAcceptanceOrder() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            submit(transfer("pay-2", "order-2", 300));
            submit(transfer("pay-3", "order-3", 100));

            // when
            ClaimBatch first = relay.claim(2, LEASE);
            ClaimBatch second = relay.claim(2, LEASE);

            // then
            assertClaimed(first, "pay-1", "pay-2");
            assertClaimed(second, "pay-3");
        }
    }

    @Nested
    @Order(6)
    @DisplayName("When a result is written for a claimed command")
    class WritingResults {

        ClaimedCommand pay1;

        @BeforeEach
        void givenPay1IsClaimed() {
            submit(transfer("pay-1", "order-1", 500));
            pay1 = claimOnly("pay-1");
        }

        @Test
        @DisplayName("Writing success (Created) makes it applied, and the handler receives applied once")
        void successApplies() {
            // when
            WriteResult result = relay.complete(pay1, Created);

            // then
            assertWritten(result);
            assertQueryState("pay-1", State.APPLIED);
            assertHandlerRanOnceFor("pay-1", State.APPLIED);
        }

        @Test
        @DisplayName("The status the handler receives has a completion time — the handler sees an ended command")
        void theHandlerSeesTheCompletionTime() {
            // when
            relay.complete(pay1, Created);

            // then
            assertThat(handler.completedFor("pay-1").get(0).completedAt()).as("completion time the handler received").isPresent();
        }

        @Test
        @DisplayName("Writing insufficient balance makes it rejected, with a business rejection (ExceedsCredits) as the reason")
        void aBusinessRejectionRejects() {
            // when
            relay.complete(pay1, ExceedsCredits);

            // then
            assertQueryState("pay-1", State.REJECTED);
            assertRejection("pay-1", ResultCategory.BUSINESS, ExceedsCredits);
        }

        @Test
        @DisplayName("Writing a content conflict makes it a conflict, and the reason is kept")
        void aContentConflictConflicts() {
            // when
            relay.complete(pay1, ExistsWithDifferentAmount);

            // then
            assertQueryState("pay-1", State.CONFLICT);
            assertRejection("pay-1", ResultCategory.CONFLICT, ExistsWithDifferentAmount);
        }

        @Test
        @DisplayName("Recording the submission before sending to TB makes it read as in progress")
        void recordingASubmissionShowsInProgress() {
            // when
            WriteResult result = relay.markSubmitted(pay1);

            // then
            assertWritten(result);
            assertQueryState("pay-1", State.IN_PROGRESS);
        }

        @Test
        @DisplayName("A command released after no response is not claimed before the backoff passes")
        void aReleasedCommandWaitsForItsBackoff() {
            // given
            assertWritten(relay.release(pay1));
            storage.elapse(BACKOFF.dividedBy(2));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("A released command is claimed again with the same transfer id after the backoff — an unresolved send is resolved only by resubmitting the same id")
        void aReleasedCommandIsClaimedAgainWithTheSameTransferId() {
            // given
            relay.markSubmitted(pay1);
            relay.release(pay1);
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("pay-1");

            // then
            assertThat(again.submission().transferId()).as("transfer id of the command claimed again").isEqualTo(BigInteger.ONE);
            assertThat(again.leaseToken()).as("token of the repeated claim").isEqualTo(2);
        }

        @Test
        @DisplayName("A command released after being sent once reads as in progress — an unresolved send does not go back to accepted")
        void aSubmittedThenReleasedCommandStaysInProgress() {
            // when
            relay.markSubmitted(pay1);
            relay.release(pay1);

            // then
            assertQueryState("pay-1", State.IN_PROGRESS);
        }
    }

    @Nested
    @Order(7)
    @DisplayName("When the earlier worker writes after its lease ended and another worker claimed the command — G5")
    class LateWorker {

        ClaimedCommand byWorker1;
        ClaimedCommand byWorker2;

        @BeforeEach
        void givenWorker2TookOverPay1() {
            submit(transfer("pay-1", "order-1", 500));
            byWorker1 = claimOnly("pay-1");
            storage.elapse(LEASE.plusSeconds(1));
            byWorker2 = claimOnly("pay-1");
        }

        @Test
        @DisplayName("The earlier worker's result write hits zero rows, the handler does not run, and the command stays in progress")
        void theLateResultIsFenced() {
            // when
            WriteResult result = relay.complete(byWorker1, Created);

            // then
            assertFenced(result);
            assertHandlerNeverRanFor("pay-1");
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("The earlier worker's submission record also hits zero rows")
        void theLateSubmissionIsFenced() {
            // when
            WriteResult result = relay.markSubmitted(byWorker1);

            // then
            assertFenced(result);
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("The new worker's result takes effect, and the handler runs only once")
        void theCurrentWorkerWins() {
            // given
            relay.complete(byWorker1, Created);

            // when
            WriteResult result = relay.complete(byWorker2, Created);

            // then
            assertWritten(result);
            assertHandlerRanOnceFor("pay-1", State.APPLIED);
        }

        @Test
        @DisplayName("Writing again to an ended command with the same token hits zero rows, and the handler does not run again")
        void aFinishedCommandIgnoresRepeatedWrites() {
            // given
            relay.complete(byWorker2, Created);

            // when
            WriteResult result = relay.complete(byWorker2, Created);

            // then
            assertFenced(result);
            assertHandlerRanOnceFor("pay-1", State.APPLIED);
        }

        @Test
        @DisplayName("Writing to a missing command hits zero rows")
        void anUnknownCommandIsFenced() {
            // given
            ClaimedCommand ghost = new ClaimedCommand(byWorker2.rowId() + 1000, "never-accepted", "order-1",
                    byWorker2.leaseToken(), false, Optional.empty(), byWorker2.submission());

            // then
            assertFenced(relay.complete(ghost, Created));
        }
    }

    @Nested
    @Order(8)
    @DisplayName("When the outcome handler fails — G6")
    class HandlerFails {

        ClaimedCommand pay1;

        @BeforeEach
        void givenTheHandlerWillFailOnce() {
            submit(transfer("pay-1", "order-1", 500));
            pay1 = claimOnly("pay-1");
            relay.markSubmitted(pay1);
            handler.failNextCalls(1);
        }

        @Test
        @DisplayName("The handler failure comes back as the result, writing the result is undone, and the command stays in progress")
        void theResultIsRolledBackWithTheHandler() {
            // when
            WriteResult result = relay.complete(pay1, ExceedsCredits);

            // then
            assertHandlerFailed(result);
            assertQueryState("pay-1", State.IN_PROGRESS);
            assertThat(status("pay-1").rejection()).as("rejection reason of the undone result").isEmpty();
            assertHandlerNeverRanFor("pay-1");
        }

        @Test
        @DisplayName("It is not claimed again before the backoff passes")
        void theFailedCommandWaitsForItsBackoff() {
            // given
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF.dividedBy(2));

            // then
            assertNothingClaimed(relay.claim(10, LEASE));
        }

        @Test
        @DisplayName("When claimed again after the backoff, it carries the TB result received earlier — so it is not sent to TB again")
        void theKnownResultComesBackWithTheNextClaim() {
            // given
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("pay-1");

            // then
            assertThat(again.knownResult()).as("result carried by the command claimed again").contains(ExceedsCredits);
        }

        @Test
        @DisplayName("Failing again doubles the wait — the handler failure count must be kept for the wait to grow")
        void aSecondFailureWaitsTwiceAsLong() {
            // given
            handler.failNextCalls(2);
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF);
            ClaimedCommand again = claimOnly("pay-1");
            assertHandlerFailed(relay.complete(again, ExceedsCredits));

            // when
            // PG's now() keeps moving, so instead of hitting the boundary, step forward by half the wait
            storage.elapse(BACKOFF);
            ClaimBatch tooEarly = relay.claim(10, LEASE);
            storage.elapse(BACKOFF);
            ClaimBatch onTime = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(tooEarly);
            assertClaimed(onTime, "pay-1");
        }

        @Test
        @DisplayName("Claiming again and ending with the same result runs the handler once and ends as rejected")
        void retryingTheHandlerFinishesTheCommand() {
            // given
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF);
            ClaimedCommand again = claimOnly("pay-1");

            // when
            WriteResult result = relay.complete(again, again.knownResult().orElseThrow());

            // then
            assertWritten(result);
            assertQueryState("pay-1", State.REJECTED);
            assertHandlerRanOnceFor("pay-1", State.REJECTED);
        }
    }

    @Nested
    @Order(9)
    @DisplayName("When the outcome handler throws an Error — G6")
    class HandlerCrashes {

        ClaimedCommand pay1;

        @BeforeEach
        void givenTheHandlerWillCrashOnce() {
            submit(transfer("pay-1", "order-1", 500));
            pay1 = claimOnly("pay-1");
            relay.markSubmitted(pay1);
            handler.crashNextCall();
        }

        @Test
        @DisplayName("The Error is not swallowed but rethrown unchanged — JVM-level problems are not hidden")
        void theErrorIsRethrown() {
            // when
            ThrowingCallable completing = () -> relay.complete(pay1, ExceedsCredits);

            // then
            assertThatThrownBy(completing)
                    .as("expected the Error thrown by the handler to come out unchanged")
                    .isInstanceOf(RecordingHandler.HandlerCrash.class);
        }

        @Test
        @DisplayName("Writing the result is undone, so the command does not remain ended without the handler's effects")
        void theResultIsRolledBack() {
            // when
            catchThrowable(() -> relay.complete(pay1, ExceedsCredits));

            // then
            assertQueryState("pay-1", State.IN_PROGRESS);
            assertHandlerNeverRanFor("pay-1");
        }

        @Test
        @DisplayName("The received TB result is kept and carried when claimed again after the backoff — so a resend to TB does not lose the original reason")
        void theKnownResultIsKept() {
            // given
            catchThrowable(() -> relay.complete(pay1, ExceedsCredits));
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("pay-1");

            // then
            assertThat(again.knownResult()).as("result carried by the command claimed again").contains(ExceedsCredits);
        }
    }

    @Nested
    @Order(10)
    @DisplayName("When the earlier command of the same key ends — G4")
    class AfterTheEarlierCommandFinishes {

        @Test
        @DisplayName("The later command is claimed")
        void theNextCommandIsClaimed() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            submit(transfer("pay-2", "order-1", 300));
            finished("pay-1", Created);

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-2");
        }

        @Test
        @DisplayName("If the reservation was applied, the post goes to TB carrying the reservation's transfer id as the pending id")
        void aPostOfAnAppliedReservationGoesToTigerBeetle() {
            // given
            submit(reserve("hold-1", "order-1", 500));
            submit(post("capture-1", "order-1", "hold-1"));
            finished("hold-1", Created);

            // when
            ClaimedCommand capture = claimOnly("capture-1");

            // then
            assertThat(capture.skip()).as("whether it is to be skipped").isFalse();
            assertThat(capture.submission().body().pendingTransferId()).as("reservation transfer id the post points to")
                    .contains(BigInteger.ONE);
        }

        @Test
        @DisplayName("If the reservation was rejected, the post is claimed as one to skip")
        void aPostOfARejectedReservationIsClaimedToBeSkipped() {
            // given
            submit(reserve("hold-1", "order-1", 500));
            submit(post("capture-1", "order-1", "hold-1"));
            finished("hold-1", ExceedsCredits);

            // when
            ClaimedCommand capture = claimOnly("capture-1");

            // then
            assertThat(capture.skip()).as("whether it is to be skipped").isTrue();
        }

        @Test
        @DisplayName("Skipping ends it as skipped, and the handler runs once with the reason 'reservation not applied' and no rejection reason")
        void skippingFinishesWithoutAReason() {
            // given
            submit(reserve("hold-1", "order-1", 500));
            submit(post("capture-1", "order-1", "hold-1"));
            finished("hold-1", ExceedsCredits);
            ClaimedCommand capture = claimOnly("capture-1");

            // when
            WriteResult result = relay.skip(capture);

            // then
            assertWritten(result);
            assertQueryState("capture-1", State.SKIPPED);
            assertThat(status("capture-1").rejection()).as("rejection reason of the skip").isEmpty();
            assertThat(status("capture-1").skipReason()).as("skip reason").contains(SkipReason.RESERVATION_NOT_APPLIED);
            assertHandlerRanOnceFor("capture-1", State.SKIPPED);
        }

        @Test
        @DisplayName("If the handler fails on a skip, it is claimed again as one to skip after the backoff — with no known result")
        void aFailedSkipIsSkippedAgain() {
            // given
            submit(reserve("hold-1", "order-1", 500));
            submit(post("capture-1", "order-1", "hold-1"));
            finished("hold-1", ExceedsCredits);
            ClaimedCommand capture = claimOnly("capture-1");
            handler.failNextCalls(1);
            assertHandlerFailed(relay.skip(capture));
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("capture-1");

            // then
            assertThat(again.skip()).as("whether it is to be skipped again").isTrue();
            assertThat(again.knownResult()).as("result left by the skip").isEmpty();
        }
    }

    @Nested
    @Order(11)
    @DisplayName("When worker steps are called inside an app transaction — G6")
    class WorkerStepsInsideATransaction {

        @Test
        @DisplayName("The claim is refused, and nothing is claimed")
        void claimingInsideATransactionIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ThrowingCallable claimingInside = () -> storage.transactions().inTransaction(() -> relay.claim(10, LEASE));

            // then
            assertRefusedAsAWorkerStepInsideATransaction(claimingInside);
            assertClaimed(relay.claim(10, LEASE), "pay-1");
        }

        @Test
        @DisplayName("Writing the result is refused, and a handler failure does not leave a half-committed result")
        void completingInsideATransactionIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand pay1 = claimOnly("pay-1");
            handler.failNextCalls(1);

            // when
            ThrowingCallable completingInside =
                    () -> storage.transactions().inTransaction(() -> relay.complete(pay1, ExceedsCredits));

            // then
            assertRefusedAsAWorkerStepInsideATransaction(completingInside);
            assertQueryState("pay-1", State.ACCEPTED);
            assertHandlerNeverRanFor("pay-1");
        }

        @Test
        @DisplayName("Recording the submission is refused, and the command does not become in progress")
        void markingSubmittedInsideATransactionIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand pay1 = claimOnly("pay-1");

            // when
            ThrowingCallable markingInside = () -> storage.transactions().inTransaction(() -> relay.markSubmitted(pay1));

            // then
            assertRefusedAsAWorkerStepInsideATransaction(markingInside);
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("Releasing is refused")
        void releasingInsideATransactionIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand pay1 = claimOnly("pay-1");

            // when
            ThrowingCallable releasingInside = () -> storage.transactions().inTransaction(() -> relay.release(pay1));

            // then
            assertRefusedAsAWorkerStepInsideATransaction(releasingInside);
        }

        @Test
        @DisplayName("Skipping is refused, and the handler does not run — skipping also calls the handler, so G6 applies the same way")
        void skippingInsideATransactionIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand pay1 = claimOnly("pay-1");

            // when
            ThrowingCallable skippingInside = () -> storage.transactions().inTransaction(() -> relay.skip(pay1));

            // then
            assertRefusedAsAWorkerStepInsideATransaction(skippingInside);
            assertHandlerNeverRanFor("pay-1");
        }
    }

    @Nested
    @Order(12)
    @DisplayName("Given a post whose referenced reservation row is missing — F10")
    class WhenTheReservationRowIsMissing {

        @BeforeEach
        void givenCapture1WhoseHold1RowIsMissing() {
            acceptedPastTheChecks(postRow("capture-1", "order-1", 2, "hold-1", 101));
        }

        @Test
        @DisplayName("Claiming reports it as halted without sending to TB — if the reservation ended as a conflict, another command's transfer sits under the same TB id")
        void claimingHaltsIt() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("Claiming makes the row halted and raises the token by 1")
        void haltingParksTheRow() {
            // when
            relay.claim(10, LEASE);

            // then
            assertRow("capture-1", haltedRow(1));
        }
    }

    @Nested
    @Order(13)
    @DisplayName("Given a post with an earlier seq than the reservation it refers to under the same key — F9")
    class WhenThePostIsAheadOfItsReservation {

        @BeforeEach
        void givenCapture1AtSeq1AheadOfHold1AtSeq2() {
            acceptedPastTheChecks(reserveRow("hold-1", "order-1", 2));
            acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102));
        }

        @Test
        @DisplayName("Claiming does not take it and reports it as halted — sending it first would reject it wrongly and leave the reserved amount held")
        void claimingHaltsIt() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("Claiming makes the row halted and raises the token by 1 — nothing was sent to TB")
        void haltingParksTheRow() {
            // when
            relay.claim(10, LEASE);

            // then
            assertRow("capture-1", haltedRow(1));
        }

        @Test
        @DisplayName("A query shows it as halted — so it does not look accepted and hide the cause")
        void theQueryShowsItHalted() {
            // when
            relay.claim(10, LEASE);

            // then
            assertQueryState("capture-1", State.HALTED);
            assertHaltReason("capture-1", HaltReason.RESERVATION_NOT_EARLIER);
        }

        @Test
        @DisplayName("A halted command is not reported again on the next claim — the report happens once, when it halts")
        void itIsReportedOnce() {
            // given
            relay.claim(10, LEASE);

            // when
            ClaimBatch next = relay.claim(10, LEASE);

            // then
            assertNothingHalted(next);
        }

        @Test
        @DisplayName("Later commands of a halted key wait until an operator resolves it — the order within the key is not skipped (G4)")
        void laterCommandsInTheKeyWait() {
            // given
            relay.claim(10, LEASE);

            // when
            ClaimBatch next = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(next);
        }

        @Test
        @DisplayName("Commands of other ordering keys are claimed regardless of the halted command")
        void otherKeysAreStillClaimed() {
            // given
            submit(transfer("pay-1", "order-2", 500));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertClaimed(batch, "pay-1");
        }

        @Test
        @DisplayName("Even when claiming one at a time, a command of another key is claimed after the halt — the halted command does not take up the limit")
        void theHaltedCommandDoesNotUseUpTheLimit() {
            // given
            relay.claim(10, LEASE);
            submit(transfer("pay-1", "order-2", 500));

            // when
            ClaimBatch batch = relay.claim(1, LEASE);

            // then
            assertClaimed(batch, "pay-1");
        }
    }

    @Nested
    @Order(14)
    @DisplayName("When an operator abandons a halted post — F9")
    class AbandoningAHaltedPost {

        @BeforeEach
        void givenCapture1IsHaltedAheadOfHold1() {
            acceptedPastTheChecks(reserveRow("hold-1", "order-1", 2));
            acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102));
            relay.claim(10, LEASE);
        }

        @Test
        @DisplayName("It ends as skipped, and the handler receives the reason 'abandoned by operator' once — it never went to TB")
        void itEndsSkippedAsAbandoned() {
            // when
            WriteResult result = relay.abandonHalted("capture-1");

            // then
            assertWritten(result);
            assertHandlerRanOnceAsAbandoned("capture-1");
            assertThat(status("capture-1").submitCount()).as("times sent to TB").isZero();
        }

        @Test
        @DisplayName("The key is unblocked and the reservation behind it is claimed")
        void theKeyIsReleased() {
            // given
            relay.abandonHalted("capture-1");

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "hold-1");
        }

        @Test
        @DisplayName("If the handler fails, the command stays halted and the handler failure comes back — the operator can abandon again")
        void aFailingHandlerLeavesItHalted() {
            // given
            handler.failNextCalls(1);

            // when
            WriteResult result = relay.abandonHalted("capture-1");

            // then
            assertHandlerFailed(result);
            assertRow("capture-1", haltedRow(1));
        }

        @Test
        @DisplayName("If the handler throws an Error, the Error comes out unchanged and the command stays halted")
        void aCrashingHandlerLeavesItHalted() {
            // given
            handler.crashNextCall();

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("capture-1");

            // then
            assertThatThrownBy(abandoning).as("expected the Error thrown by the handler to come out unchanged")
                    .isInstanceOf(RecordingHandler.HandlerCrash.class);
            assertRow("capture-1", haltedRow(1));
        }
    }

    @Nested
    @Order(15)
    @DisplayName("When abandoning a command that is not halted")
    class AbandoningSomethingElse {

        @Test
        @DisplayName("An accepted command is refused, and nothing changes")
        void anAcceptedCommandIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("pay-1");

            // then
            assertThatThrownBy(abandoning).as("expected a refusal because the command is not halted")
                    .isInstanceOfSatisfying(CannotAbandonException.class, refused -> assertThat(refused.reason())
                            .as("refusal reason").isEqualTo(CannotAbandonException.Reason.NOT_HALTED))
                    .hasMessageContaining("HALTED");
            assertRow("pay-1", CommandRow.accepted());
        }

        @Test
        @DisplayName("A command that was never accepted is refused")
        void anUnknownCommandIsRefused() {
            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("never-accepted");

            // then
            assertThatThrownBy(abandoning).as("expected a refusal because the command does not exist")
                    .isInstanceOf(CommandNotFoundException.class).hasMessageContaining("never-accepted");
        }

        @Test
        @DisplayName("A commandId that contains a lone surrogate is refused as never accepted, and the different command whose id has '?' is untouched (F20)")
        void aLoneSurrogateIdIsRefusedAsUnknown() {
            // given
            submit(transfer("pay-?", "order-1", 500));

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("pay-\uD800");

            // then
            assertThatThrownBy(abandoning).as("expected a refusal as a missing command because the id cannot be accepted")
                    .isInstanceOf(CommandNotFoundException.class).hasMessageContaining("No command was accepted with this id");
            assertRow("pay-?", CommandRow.accepted());
        }

        @Test
        @DisplayName("Calling it inside a transaction is refused — so a handler failure is not buried in the outer transaction (G6)")
        void abandoningInsideATransactionIsRefused() {
            // given
            acceptedPastTheChecks(reserveRow("hold-1", "order-1", 2));
            acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102));
            relay.claim(10, LEASE);

            // when
            ThrowingCallable abandoningInside =
                    () -> storage.transactions().inTransaction(() -> relay.abandonHalted("capture-1"));

            // then
            assertRefusedAsAWorkerStepInsideATransaction(abandoningInside);
        }
    }

    @Nested
    @Order(21)
    @DisplayName("When a row with broken data becomes a claim candidate — F29")
    class AMalformedRowIsACandidate {

        @Test
        @DisplayName("Only that row is reported as halted, and commands of other keys are still claimed — so one bad row does not stop the whole relay")
        void aMalformedRowIsHaltedAndOtherKeysKeepFlowing() {
            assumeTrue(storage.allowsMalformedRows(), "this store's schema blocks such rows (F31)");
            // given: a post row with an empty reservation reference (acceptance checks cannot produce it), and a normal
            // transfer under another key
            acceptedPastTheChecks(new NewCommand("capture-1", "order-1", 1, Optional.empty(), BigInteger.valueOf(101),
                    "hash-of-capture-1", new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO,
                            BigInteger.valueOf(500), 0, 0, 0, Optional.empty(), 0, 0)));
            submit(transfer("pay-2", "order-2", 500));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-2");
            assertHalted(batch, "capture-1");
            assertThat(batch.problems()).as("cause for the problem candidate").singleElement()
                    .satisfies(problem -> assertThat(problem.halted()).as("whether it was parked as halted").isTrue());
            assertHaltReason("capture-1", HaltReason.MALFORMED_ROW);
        }
    }

    @Nested
    @Order(20)
    @DisplayName("When the row of a claimed command is deleted and the same commandId is accepted again — G5")
    class TheRowWasReplaced {

        @Test
        @DisplayName("A write by the earlier claimer hits zero rows — it does not write to the new row with the same token")
        void aWriteForTheOldRowIsFencedEvenIfTheNewRowHasTheSameToken() {
            // given: pay-1 was claimed with token 1, then its row was deleted, and the newly accepted pay-1 was also
            // claimed with token 1
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand old = claimOnly("pay-1");
            storage.deleteCommand("pay-1");
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand renewed = claimOnly("pay-1");
            if (renewed.leaseToken() != old.leaseToken() || renewed.rowId() == old.rowId()) {
                throw new IllegalStateException("given setup failed: not a new row with the same token: " + old + " / " + renewed);
            }

            // when
            WriteResult result = relay.complete(old, Created);

            // then
            assertFenced(result);
            assertHandlerNeverRanFor("pay-1");
        }
    }

    @Nested
    @Order(19)
    @DisplayName("When a sent command is released repeatedly — backoff")
    class ReleasingAgainAndAgain {

        @Test
        @DisplayName("After the second release the wait doubles, so it is not claimed again after only the first wait")
        void theWaitGrowsWithEachRelease() {
            // given: sent and released twice (first wait BACKOFF, second wait 2×BACKOFF)
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand first = claimOnly("pay-1");
            relay.markSubmitted(first);
            relay.release(first);
            storage.elapse(BACKOFF);
            ClaimedCommand second = claimOnly("pay-1");
            relay.markSubmitted(second);
            relay.release(second);

            // when
            storage.elapse(BACKOFF);
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(batch);
        }
    }

    @Nested
    @Order(18)
    @DisplayName("When the same post is submitted again after it ended and its referenced reservation row is gone — F28")
    class ResubmittingAfterTheReservationIsGone {

        CommandReceipt original;

        @BeforeEach
        void givenCapture1IsAppliedAndHold1IsDeleted() {
            submit(reserve("hold-1", "order-1", 500));
            relay.complete(claimOnly("hold-1"), Created);
            original = submit(post("capture-1", "order-1", "hold-1"));
            relay.complete(claimOnly("capture-1"), Created);
            storage.deleteCommand("hold-1");
        }

        @Test
        @DisplayName("The same content returns the first receipt — the resend check comes before the reference check")
        void resubmittingAFinishedPostAfterItsReservationIsGoneReturnsTheReceipt() {
            // when
            CommandReceipt again = submit(post("capture-1", "order-1", "hold-1"));

            // then
            assertThat(again).as("receipt returned again").isEqualTo(original);
        }

        @Test
        @DisplayName("Different content is refused as a content conflict, not as a missing reservation (G7)")
        void resubmittingItWithOtherContentIsAConflict() {
            // when
            ThrowingCallable resubmitting = () -> submit(LedgerCommand.post("capture-1").orderingKey("order-1")
                    .reservation("hold-1").amount(300).build());

            // then
            assertThatThrownBy(resubmitting).as("resend with the same commandId and different content")
                    .isInstanceOf(CommandConflictException.class);
        }
    }

    @Nested
    @Order(17)
    @DisplayName("When abandoning a command that halted after it was already sent to TB — F27")
    class AbandoningAHaltedCommandThatWasSent {

        @BeforeEach
        void givenHold1IsApplied() {
            submit(reserve("hold-1", "order-1", 500));
            relay.complete(claimOnly("hold-1"), Created);
            submit(post("capture-1", "order-1", "hold-1"));
        }

        @Test
        @DisplayName("A post that was sent once and released is refused, stays halted, and the handler does not run — TB may have applied it")
        void aReleasedCommandIsRefused() {
            // given: capture-1 was sent and released, then its referenced reservation row disappeared and it halted
            ClaimedCommand capture = claimOnly("capture-1");
            relay.markSubmitted(capture);
            relay.release(capture);
            haltedAfterItsReservationIsGone("capture-1");

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("capture-1");

            // then
            assertRefusedAsAlreadySent(abandoning);
            assertQueryState("capture-1", State.HALTED);
            assertHandlerNeverRanFor("capture-1");
        }

        @Test
        @DisplayName("A post with a known TB result is refused — only the handler failed, and TB recorded it")
        void aCommandWithAKnownResultIsRefused() {
            // given: TB recorded capture-1 but the handler failed, then its referenced reservation row disappeared and
            // it halted
            handler.failNextCalls(1);
            ClaimedCommand capture = claimOnly("capture-1");
            relay.markSubmitted(capture);
            relay.complete(capture, Created);
            haltedAfterItsReservationIsGone("capture-1");

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("capture-1");

            // then
            assertRefusedAsAlreadySent(abandoning);
            assertQueryState("capture-1", State.HALTED);
        }

        private void haltedAfterItsReservationIsGone(String commandId) {
            storage.deleteCommand("hold-1");
            storage.elapse(BACKOFF.multipliedBy(4));
            ClaimBatch batch = relay.claim(10, LEASE);
            if (!batch.halted().contains(commandId)) {
                throw new IllegalStateException("given setup failed: " + commandId + " did not halt: " + batch);
            }
        }

        private void assertRefusedAsAlreadySent(ThrowingCallable abandoning) {
            assertThatThrownBy(abandoning).as("abandon of a command already sent to TB is refused")
                    .isInstanceOfSatisfying(CannotAbandonException.class, refused -> assertThat(refused.reason())
                            .as("refusal reason").isEqualTo(CannotAbandonException.Reason.ALREADY_SUBMITTED))
                    .hasMessageContaining("was already sent to TB");
        }
    }

    @Nested
    @Order(16)
    @DisplayName("When a reservation is submitted and then referred to in the same app transaction")
    class ReferencingAReservationOfTheSameTransaction {

        @Test
        @DisplayName("The post is refused — the referenced reservation must already be committed")
        void thePostIsRefused() {
            // when
            ThrowingCallable postInTheSameTransaction = () -> storage.transactions().inTransaction(() -> {
                relay.submit(reserve("hold-1", "order-1", 500));
                return relay.submit(post("capture-1", "order-1", "hold-1"));
            });

            // then
            assertRejectedAsMissingReservation(postInTheSameTransaction, "capture-1");
            assertThat(relay.status("capture-1")).as("refused post").isEmpty();
        }

        @Test
        @DisplayName("Submitting in the next transaction, after the reservation commits, is accepted")
        void aLaterTransactionMayReferenceIt() {
            // given
            submit(reserve("hold-1", "order-1", 500));

            // when
            submit(post("capture-1", "order-1", "hold-1"));

            // then
            assertQueryState("capture-1", State.ACCEPTED);
        }
    }

    // ── Words that build commands: amounts and keys are not hidden ──────────────────────

    protected static LedgerCommand transfer(String commandId, String orderingKey, long amount) {
        return LedgerCommand.transfer(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(amount).ledger(1).code(1).build();
    }

    protected static LedgerCommand reserve(String commandId, String orderingKey, long amount) {
        return LedgerCommand.reserve(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(amount).ledger(1).code(1).build();
    }

    protected static LedgerCommand post(String commandId, String orderingKey, String reservationCommandId) {
        return LedgerCommand.post(commandId).orderingKey(orderingKey).reservation(reservationCommandId).build();
    }

    protected static LedgerCommand voidOf(String commandId, String orderingKey, String reservationCommandId) {
        return LedgerCommand.voidReservation(commandId).orderingKey(orderingKey).reservation(reservationCommandId).build();
    }

    // ── Rows that skip acceptance checks: build the state after a bug or a direct DB edit. Transfer ids are 100+seq
    // ──────

    protected static NewCommand reserveRow(String commandId, String orderingKey, long seq) {
        return new NewCommand(commandId, orderingKey, seq, Optional.empty(), TestTransferIds.transferIdFor(orderingKey, seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    protected static NewCommand postRow(String commandId, String orderingKey, long seq, String reservationCommandId,
            long pendingTransferId) {
        return new NewCommand(commandId, orderingKey, seq, Optional.of(reservationCommandId),
                TestTransferIds.transferIdFor(orderingKey, seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(BigInteger.valueOf(pendingTransferId)), 0, 0));
    }

    protected static CommandRow haltedRow(long token) {
        return new CommandRow(StoredState.HALTED, token, 0, Optional.empty(), Optional.empty(), 0);
    }

    // ── Words for preconditions and checks ────────────────────────────────────────────

    /** Submits inside a transaction, as the app does. */
    protected CommandReceipt submit(LedgerCommand command) {
        return storage.transactions().inTransaction(() -> relay.submit(command));
    }

    protected CommandStatus status(String commandId) {
        return relay.status(commandId).orElseThrow(
                () -> new AssertionError("Expected " + commandId + " to be accepted but the query found nothing"));
    }

    protected void assertHaltReason(String commandId, HaltReason expected) {
        HaltReason reason = storage.transactions().inTransaction(() -> storage.store().find(commandId)).orElseThrow()
                .halt().orElseThrow(() -> new AssertionError("No halt record for " + commandId)).reason();
        assertThat(reason).as("halt reason of %s", commandId).isEqualTo(expected);
    }

    protected void assertQueryState(String commandId, State expected) {
        assertThat(status(commandId).state()).as("query state of %s", commandId).isEqualTo(expected);
    }

    protected static void assertRejectedAsConflict(ThrowingCallable accepting, String commandId) {
        assertThatThrownBy(accepting)
                .as("expected %s to be refused as a content conflict", commandId)
                .isInstanceOf(CommandConflictException.class)
                .satisfies(e -> assertThat(((CommandConflictException) e).commandId()).isEqualTo(commandId));
    }

    protected static void assertRejectedAsMissingReservation(ThrowingCallable accepting, String commandId) {
        assertThatThrownBy(accepting)
                .as("expected %s to be refused because its referenced reservation was not found", commandId)
                .isInstanceOf(ReservationNotFoundException.class)
                .satisfies(e -> assertThat(((ReservationNotFoundException) e).commandId()).isEqualTo(commandId));
    }

    /** Precondition: only this command is claimed. Otherwise it stops with a given setup failure. */
    protected ClaimedCommand claimOnly(String commandId) {
        ClaimBatch batch = relay.claim(10, LEASE);
        if (batch.claimed().size() != 1 || !batch.claimed().get(0).commandId().equals(commandId)) {
            throw new IllegalStateException("given setup failed: expected only " + commandId + " to be claimed, but got "
                    + idsOf(batch));
        }
        return batch.claimed().get(0);
    }

    protected static void assertClaimed(ClaimBatch batch, String... commandIds) {
        assertThat(idsOf(batch)).as("claimed commands (in sendable order)").isEqualTo(Arrays.asList(commandIds));
    }

    protected static void assertNothingClaimed(ClaimBatch batch) {
        assertThat(idsOf(batch)).as("expected nothing to be claimed").isEmpty();
    }

    private static List<String> idsOf(ClaimBatch batch) {
        return batch.claimed().stream().map(ClaimedCommand::commandId).toList();
    }

    /** Precondition: this command ended with this TB result. */
    protected void finished(String commandId, LedgerResultCode code) {
        ClaimedCommand claimed = claimOnly(commandId);
        if (!(relay.complete(claimed, code) instanceof WriteResult.Written)) {
            throw new IllegalStateException("given setup failed: could not end " + commandId + " with " + code);
        }
    }

    protected static void assertWritten(WriteResult result) {
        assertThat(result).as("expected the write to take effect").isEqualTo(new WriteResult.Written());
    }

    protected static void assertFenced(WriteResult result) {
        assertThat(result).as("expected the write to be fenced with zero rows").isEqualTo(new WriteResult.Fenced());
    }

    protected static void assertHandlerFailed(WriteResult result) {
        assertThat(result).as("expected a handler failure to come back").isInstanceOf(WriteResult.HandlerFailed.class);
        assertThat(((WriteResult.HandlerFailed) result).cause()).as("exception thrown by the handler")
                .isInstanceOf(RecordingHandler.HandlerFailure.class);
    }

    protected void assertHandlerRanOnceFor(String commandId, State state) {
        assertThat(handler.completedFor(commandId).stream().map(CommandStatus::state).toList())
                .as("states received by handler calls that ran to completion for %s", commandId)
                .containsExactly(state);
    }

    protected void assertHandlerNeverRanFor(String commandId) {
        assertThat(handler.completedFor(commandId)).as("handler calls that ran to completion for %s", commandId).isEmpty();
    }

    protected void assertRejection(String commandId, ResultCategory category, LedgerResultCode code) {
        assertThat(status(commandId).rejection()).as("rejection reason of %s", commandId)
                .contains(new Rejection(category, code));
    }

    protected static void assertRefusedAsAWorkerStepInsideATransaction(ThrowingCallable step) {
        assertThatThrownBy(step)
                .as("expected the worker step to be refused inside a transaction")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    /** Precondition: this row goes straight into the store, skipping acceptance checks. */
    protected void acceptedPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given setup failed: " + row.commandId() + " already existed");
        }
    }

    protected void assertRow(String commandId, CommandRow expected) {
        CommandRow row = storage.transactions().inTransaction(() -> storage.store().find(commandId))
                .orElseThrow(() -> new AssertionError("Expected " + commandId + " to be stored but did not find it")).row();
        assertThat(row).as("row of %s", commandId).isEqualTo(expected);
    }

    protected static void assertHalted(ClaimBatch batch, String... commandIds) {
        assertThat(batch.halted()).as("commands reported as halted").isEqualTo(Arrays.asList(commandIds));
    }

    protected static void assertNothingHalted(ClaimBatch batch) {
        assertThat(batch.halted()).as("expected no halted commands").isEmpty();
    }

    protected void assertHandlerRanOnceAsAbandoned(String commandId) {
        assertThat(handler.completedFor(commandId).stream()
                .map(outcome -> outcome.state() + "/" + outcome.skipReason().map(Enum::name).orElse("-")).toList())
                .as("handler calls that ran to completion for %s (state/skip reason)", commandId)
                .containsExactly("SKIPPED/ABANDONED_BY_OPERATOR");
    }

    /** The app transaction fails on purpose. */
    protected static final class AppFailure extends RuntimeException {
        AppFailure() {
            super("The app rolls back on purpose");
        }
    }
}
