package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.Backlog;
import io.github.ledgerrelay.core.spi.HaltMark;
import io.github.ledgerrelay.core.HaltReason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.SkipReason;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.QueuedCommand;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link CommandStore} implementation must keep. The store makes no decisions, so this contract
 * covers only the meaning of insert, find, lock-and-read, conditional write and transactions. Business rules are in
 * {@code LedgerRelayContract}.
 * Each implementation has one test that extends this class.
 */
public abstract class CommandStoreContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration BACKOFF = Duration.ofSeconds(5);
    /** The fixed observation time for "it is blocked" (docs/testing.md) */
    protected static final Duration BLOCKED = Duration.ofMillis(300);

    protected StorageUnderTest storage;
    protected OrderingKeys keys;
    protected CommandStore store;

    /** Builds one empty set of storage for each test. */
    protected abstract StorageUnderTest createStorage();

    @BeforeEach
    protected void givenAnEmptyStore() {
        storage = createStorage();
        keys = storage.keys();
        store = storage.store();
    }

    @Nested
    @Order(1)
    @DisplayName("When seqs are issued")
    class IssuingSequenceNumbers {

        @Test
        @DisplayName("Each ordering key counts up from 1, independently of other keys")
        void eachKeyCountsFromOne() {
            // when
            long first = inTx(() -> keys.lockKeyAndTakeSeq("order-1"));
            long second = inTx(() -> keys.lockKeyAndTakeSeq("order-1"));
            long otherKey = inTx(() -> keys.lockKeyAndTakeSeq("order-2"));

            // then
            assertThat(first).as("first seq of order-1").isEqualTo(1);
            assertThat(second).as("second seq of order-1").isEqualTo(2);
            assertThat(otherKey).as("first seq of order-2").isEqualTo(1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a row is inserted and found")
    class InsertingAndFinding {

        @Test
        @DisplayName("An inserted row is found in the just-accepted state, with the inserted values unchanged")
        void anInsertedRowIsFoundAsAccepted() {
            // when
            Optional<CommandRecord> existing = inTx(() -> store.insertIfAbsent(newTransfer("pay-1", "order-1", 1)));

            // then
            assertThat(existing).as("existing row").isEmpty();
            CommandRecord found = record("pay-1");
            assertThat(found.row()).as("state of the row just inserted").isEqualTo(CommandRow.accepted());
            assertThat(found.orderingKey()).as("ordering key").isEqualTo("order-1");
            assertThat(found.seq()).as("seq").isEqualTo(1);
            assertThat(found.transferId()).as("transfer id").isEqualTo(BigInteger.valueOf(101));
            assertThat(found.contentHash()).as("content hash").isEqualTo("hash-of-pay-1");
            assertThat(found.body()).as("transfer body").isEqualTo(new TransferBody(CommandKind.TRANSFER,
                    BigInteger.valueOf(1), BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0,
                    Optional.empty(), 0, 0));
            assertThat(found.completedAt()).as("completion time").isEmpty();
        }

        @Test
        @DisplayName("Inserting the same commandId again inserts nothing and returns the first row — the first content stays")
        void aSecondInsertReturnsTheFirstRow() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));

            // when
            Optional<CommandRecord> existing = inTx(() -> store.insertIfAbsent(newTransfer("pay-1", "order-2", 1)));

            // then
            assertThat(existing.map(CommandRecord::orderingKey)).as("ordering key of the returned row").contains("order-1");
            assertThat(record("pay-1").orderingKey()).as("ordering key of the remaining row").isEqualTo("order-1");
        }

        @Test
        @DisplayName("Each insert gets a different row id")
        void eachRowGetsItsOwnId() {
            // when
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));

            // then
            assertThat(record("pay-1").rowId()).as("ids of the two rows").isNotEqualTo(record("pay-2").rowId());
        }

        @Test
        @DisplayName("A row that refers to a reservation keeps the reference and the reservation's transfer id unchanged")
        void aPostKeepsItsReference() {
            // when
            inserted(newPost("capture-1", "order-1", 2, "hold-1"));

            // then
            CommandRecord found = record("capture-1");
            assertThat(found.reservationCommandId()).as("referenced reservation").contains("hold-1");
            assertThat(found.body().pendingTransferId()).as("transfer id of the reservation").contains(BigInteger.valueOf(101));
        }

        @Test
        @DisplayName("A commandId that was never inserted is not found")
        void anUnknownCommandIsNotFound() {
            // when
            Optional<CommandRecord> found = inTx(() -> store.find("never-inserted"));

            // then
            assertThat(found).as("command never inserted").isEmpty();
        }

        @Test
        @DisplayName("Unsigned maximum values stay unchanged — amount 2^128-1, user_data_64 2^64-1, ledger and user_data_32 2^32-1, code 65535")
        void unsignedMaximaRoundTrip() {
            // given
            BigInteger u128Max = BigInteger.TWO.pow(128).subtract(BigInteger.ONE);
            TransferBody widest = new TransferBody(CommandKind.RESERVE, u128Max.subtract(BigInteger.ONE),
                    u128Max.subtract(BigInteger.TWO), u128Max, 4294967295L, 65535, 4294967295L, Optional.empty(),
                    -1L, -1);

            // when
            inserted(new NewCommand("hold-max", "order-1", 1, Optional.empty(), u128Max.subtract(BigInteger.TEN),
                    "hash-of-hold-max", widest));

            // then
            assertThat(record("hold-max").body()).as("transfer body read back").isEqualTo(widest);
            assertThat(record("hold-max").transferId()).as("transfer id read back").isEqualTo(u128Max.subtract(BigInteger.TEN));
        }

        @Test
        @DisplayName("In the same transaction the row is marked as just inserted, and after commit it is not")
        void aRowIsMarkedAsAcceptedInThisTransactionUntilCommit() {
            // when
            boolean insideTheInsertingTransaction = inTx(() -> {
                store.insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                return store.find("pay-1").orElseThrow().acceptedInThisTransaction();
            });

            // then
            assertThat(insideTheInsertingTransaction).as("mark seen inside the inserting transaction").isTrue();
            assertThat(record("pay-1").acceptedInThisTransaction()).as("mark seen after commit").isFalse();
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When transactions open and close — G1, G6")
    class TransactionBoundaries {

        @Test
        @DisplayName("Throwing removes the rows inserted inside it, and the exception comes out unchanged")
        void aThrowingTransactionLeavesNoRow() {
            // when
            Runnable insertThenFail = () -> storage.transactions().inTransaction(() -> {
                store.insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                throw new Rollback();
            });

            // then
            assertThatThrownBy(insertThenFail::run).isInstanceOf(Rollback.class);
            assertThat(inTx(() -> store.find("pay-1"))).as("rolled-back row").isEmpty();
        }

        @Test
        @DisplayName("Opening again inside joins the outer transaction, so the rows disappear together when the outer one throws")
        void anInnerCallJoinsTheOuterTransaction() {
            // when
            Runnable nestedThenFail = () -> storage.transactions().inTransaction(() -> {
                storage.transactions().inTransaction(() -> store.insertIfAbsent(newTransfer("pay-1", "order-1", 1)));
                throw new Rollback();
            });

            // then
            assertThatThrownBy(nestedThenFail::run).isInstanceOf(Rollback.class);
            assertThat(inTx(() -> store.find("pay-1"))).as("row rolled back with the outer transaction").isEmpty();
        }

        @Test
        @DisplayName("Requiring an active transaction outside one is refused")
        void requiringATransactionOutsideOneFails() {
            // when
            ThrowingCallable requiringOutside = () -> storage.transactions().requireActive();

            // then
            assertThatThrownBy(requiringOutside)
                    .as("expected a refusal outside a transaction")
                    .isInstanceOf(storage.outsideTransactionError());
        }

        @Test
        @DisplayName("Requiring an active transaction inside one passes")
        void requiringATransactionInsideOnePasses() {
            // when
            ThrowingCallable requiringInside = () -> inTx(() -> storage.transactions().requireActive());

            // then
            assertThatCode(requiringInside).as("result of requiring inside a transaction").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("It looks active inside and not active outside")
        void reportsWhetherATransactionIsActive() {
            // when
            boolean inside = inTx(() -> storage.transactions().isActive());
            boolean outside = storage.transactions().isActive();

            // then
            assertThat(inside).as("active flag seen inside a transaction").isTrue();
            assertThat(outside).as("active flag seen outside a transaction").isFalse();
        }
    }

    @Nested
    @Order(4)
    @DisplayName("When claim candidates are locked")
    class LockingDueCommands {

        @Test
        @DisplayName("An inserted command is a candidate right away")
        void anInsertedCommandIsDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));

            // then
            assertDue("pay-1");
        }

        @Test
        @DisplayName("Only the unfinished head command of each ordering key is a candidate")
        void onlyTheHeadOfEachKeyIsDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));

            // then
            assertDue("pay-1");
        }

        @Test
        @DisplayName("While the head command is in progress, nothing of that key is a candidate")
        void aKeyWithAClaimedHeadHasNothingDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            claimed("pay-1", 1);

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("The later commands of a blocked key do not take up the limit — so other keys do not starve")
        void aBlockedKeyDoesNotUseUpTheLimit() {
            // given
            inserted(newTransfer("a-1", "order-a", 1));
            inserted(newTransfer("a-2", "order-a", 2));
            inserted(newTransfer("a-3", "order-a", 3));
            inserted(newTransfer("b-1", "order-b", 1));
            claimed("a-1", 1);

            // then
            assertDueWithLimit(1, "b-1");
        }

        @Test
        @DisplayName("When the head command finishes, the next command is a candidate")
        void theNextCommandIsDueOnceTheHeadFinishes() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            finished("pay-1", appliedRow(1));

            // then
            assertDue("pay-2");
        }

        @Test
        @DisplayName("A finished command is not a candidate")
        void aFinishedCommandIsNotDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            finished("pay-1", rejectedRow(1));

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("A command with a live lease is not a candidate")
        void aLiveLeaseIsNotDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            storage.elapse(LEASE.dividedBy(2));

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("At the exact moment the lease ends it is not yet a candidate — it is claimed again only when lease_expires_at < now")
        void theLeaseIsStillLiveAtItsExpiryInstant() {
            assumeTrue(storage.clockStandsStill(), "store with a running clock: cannot produce that exact moment");
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            storage.elapse(LEASE);

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("When the lease ends, the in-progress command is a candidate again")
        void anExpiredLeaseIsDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            storage.elapse(LEASE.plusSeconds(1));

            // when
            List<DueCommand> due = inTx(() -> lockCandidates(10));

            // then
            assertThat(due).as("number of candidates").hasSize(1);
            assertThat(due.get(0).command().row()).as("candidate row — still in progress, token 1").isEqualTo(claimedRow(1));
        }

        @Test
        @DisplayName("A released command is not a candidate before the backoff passes")
        void aReleasedCommandWaitsForItsBackoff() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            released("pay-1");
            storage.elapse(BACKOFF.dividedBy(2));

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("A released command is a candidate again after the backoff passes")
        void aReleasedCommandIsDueAfterItsBackoff() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            released("pay-1");
            storage.elapse(BACKOFF);

            // then
            assertDue("pay-1");
        }

        @Test
        @DisplayName("When the head command is halted, nothing of that key is a candidate — and the halted command does not take up the limit")
        void aHaltedHeadBlocksItsKeyWithoutUsingTheLimit() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            inserted(newTransfer("pay-3", "order-2", 1));
            halted("pay-1");

            // then
            assertDueWithLimit(1, "pay-3");
        }

        @Test
        @DisplayName("At most limit candidates come out at a time — in acceptance order when the times are equal")
        void atMostLimitInAcceptanceOrder() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));
            inserted(newTransfer("pay-3", "order-3", 1));

            // then
            assertDueWithLimit(2, "pay-1", "pay-2");
        }

        @Test
        @DisplayName("A released command queues at its retry time — commands of other keys accepted in the meantime go first")
        void aRetriedCommandQueuesAtItsRetryTime() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            released("pay-1");
            inserted(newTransfer("pay-2", "order-2", 1));
            storage.elapse(BACKOFF.multipliedBy(2));

            // then
            assertDue("pay-2", "pay-1");
        }

        @Test
        @DisplayName("A command whose lease expired queues at the expiry time — commands of other keys accepted in the meantime go first")
        void anExpiredLeaseQueuesAtItsExpiry() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            inserted(newTransfer("pay-2", "order-2", 1));
            storage.elapse(LEASE.plusSeconds(1));

            // then
            assertDue("pay-2", "pay-1");
        }

        @Test
        @DisplayName("A post candidate carries the seq and state of its referenced reservation — what the gate looks at")
        void aPostCarriesItsReservation() {
            // given
            inserted(newReserve("hold-1", "order-1", 1));
            inserted(newPost("capture-1", "order-1", 2, "hold-1"));
            finished("hold-1", appliedRow(1));

            // when
            List<DueCommand> due = inTx(() -> lockCandidates(10));

            // then
            assertThat(due).as("number of candidates").hasSize(1);
            assertThat(due.get(0).reservation()).as("reservation referenced by capture-1")
                    .contains(new QueuedCommand("hold-1", 1, StoredState.APPLIED));
        }

        @Test
        @DisplayName("Without the referenced reservation row the reservation field is empty — the store does not check references")
        void aMissingReservationIsEmpty() {
            // given
            inserted(newPost("capture-1", "order-1", 1, "hold-9"));

            // when
            List<DueCommand> due = inTx(() -> lockCandidates(10));

            // then
            assertThat(due).as("number of candidates").hasSize(1);
            assertThat(due.get(0).reservation()).as("missing reservation").isEmpty();
        }
    }

    @Nested
    @Order(5)
    @DisplayName("When a row is changed conditionally — G5")
    class ComparingAndSetting {

        CommandRecord pay1;

        @BeforeEach
        void givenPay1IsHeldWithToken3() {
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 3);
            pay1 = record("pay-1");
        }

        @Test
        @DisplayName("When state and token match, the row becomes the one the state machine gave")
        void aMatchingWriteLands() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(
                    new RowChange(pay1.rowId(), StoredState.CLAIMED, 3, appliedRow(3), new RowChange.Stamp.Completion())));

            // then
            assertThat(changed).as("whether it took effect").isTrue();
            assertThat(record("pay-1").row()).as("changed row").isEqualTo(appliedRow(3));
        }

        @Test
        @DisplayName("Every field of the row stays unchanged — submit count, known result, result code, handler failure count")
        void everyColumnOfTheRowIsKept() {
            // given
            CommandRow failedTwiceKnowingExceedsCredits = new CommandRow(StoredState.PENDING, 3, 2,
                    Optional.of(LedgerResultCode.ExceedsCredits), Optional.of(LedgerResultCode.ExceedsCredits), 3);

            // when
            inTx(() -> store.compareAndSet(new RowChange(pay1.rowId(), StoredState.CLAIMED, 3,
                    failedTwiceKnowingExceedsCredits, new RowChange.Stamp.RetryAfter(BACKOFF))));

            // then
            assertThat(record("pay-1").row()).as("row read back").isEqualTo(failedTwiceKnowingExceedsCredits);
        }

        @Test
        @DisplayName("The skip reason also stays unchanged")
        void theSkipReasonIsKept() {
            // given
            CommandRow abandoned = new CommandRow(StoredState.SKIPPED, 3, 0, Optional.empty(), Optional.empty(), 0,
                    Optional.of(SkipReason.ABANDONED_BY_OPERATOR));

            // when
            finished("pay-1", abandoned);

            // then
            assertThat(record("pay-1").row()).as("row read back").isEqualTo(abandoned);
        }

        @Test
        @DisplayName("A different token changes nothing")
        void anotherTokenIsRefused() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(
                    new RowChange(pay1.rowId(), StoredState.CLAIMED, 2, appliedRow(2), new RowChange.Stamp.Completion())));

            // then
            assertUnchanged(changed, claimedRow(3));
        }

        @Test
        @DisplayName("A different state changes nothing, even with the same token")
        void anotherStateIsRefused() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(
                    new RowChange(pay1.rowId(), StoredState.PENDING, 3, appliedRow(3), new RowChange.Stamp.Completion())));

            // then
            assertUnchanged(changed, claimedRow(3));
        }

        @Test
        @DisplayName("A missing row changes nothing")
        void anUnknownRowIsRefused() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(new RowChange(pay1.rowId() + 1000, StoredState.CLAIMED, 3,
                    appliedRow(3), new RowChange.Stamp.Completion())));

            // then
            assertThat(changed).as("whether the write to a missing row took effect").isFalse();
        }

        @Test
        @DisplayName("The completion time appears when stamped and is absent before")
        void completionStampsTheTime() {
            // given
            assertThat(pay1.completedAt()).as("completion time before the end").isEmpty();

            // when
            finished("pay-1", appliedRow(3));

            // then
            assertThat(record("pay-1").completedAt()).as("completion time after the end").isPresent();
        }

        @Test
        @DisplayName("When the transaction throws, the change is undone — so no result remains when the handler throws (G6)")
        void aThrowingTransactionUndoesTheWrite() {
            // when
            Runnable writeThenFail = () -> storage.transactions().inTransaction(() -> {
                store.compareAndSet(new RowChange(pay1.rowId(), StoredState.CLAIMED, 3, appliedRow(3),
                        new RowChange.Stamp.Completion()));
                throw new Rollback();
            });

            // then
            assertThatThrownBy(writeThenFail::run).isInstanceOf(Rollback.class);
            assertThat(record("pay-1").row()).as("row after rollback").isEqualTo(claimedRow(3));
            assertThat(record("pay-1").completedAt()).as("completion time after rollback").isEmpty();
        }
    }

    @Nested
    @Order(6)
    @DisplayName("When an accepted row is changed to halted — F9")
    class Halting {

        @BeforeEach
        void givenPay1IsHalted() {
            inserted(newTransfer("pay-1", "order-1", 1));
            halted("pay-1");
        }

        @Test
        @DisplayName("It reads as halted with an empty completion time — a halted command has not ended")
        void aHaltedRowIsNotFinished() {
            // when
            CommandRecord found = record("pay-1");

            // then
            assertThat(found.row()).as("halted row").isEqualTo(
                    new CommandRow(StoredState.HALTED, 1, 0, Optional.empty(), Optional.empty(), 0));
            assertThat(found.completedAt()).as("completion time of the halted row").isEmpty();
        }

        @Test
        @DisplayName("When an operator abandons it and it ends as skipped, a completion time appears")
        void abandoningStampsTheCompletionTime() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(new RowChange(record("pay-1").rowId(), StoredState.HALTED, 1,
                    new CommandRow(StoredState.SKIPPED, 1, 0, Optional.empty(), Optional.empty(), 0,
                            Optional.of(SkipReason.ABANDONED_BY_OPERATOR)),
                    new RowChange.Stamp.Completion())));

            // then
            assertThat(changed).as("whether it took effect").isTrue();
            assertThat(record("pay-1").completedAt()).as("completion time after abandon").isPresent();
        }
    }

    @Nested
    @Order(7)
    @DisplayName("When another transaction holds the rows — G4, G7 (F11~F15)")
    class WhileAnotherTransactionHoldsRows {

        @BeforeEach
        void givenAStoreWithRowLocks() {
            assumeTrue(storage.supportsRowLocks(), "store without row locks: skipping the concurrency section");
        }

        @Test
        @DisplayName("While the head command is locked, nothing of that key is a candidate — so a later command does not go first (G4)")
        void aLockedHeadHidesItsWholeKey() {
            // given: key order-1 has pay-1(1), pay-2(2). Another transaction locked pay-1 as a candidate
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            try (OtherTransaction<List<DueCommand>> worker1 = OtherTransaction.start(storage.transactions(),
                    () -> lockCandidates(10))) {
                worker1.awaitWork();

                // when: another worker looks for claim candidates
                List<DueCommand> due = inTx(() -> lockCandidates(10));

                // then
                assertThat(due).as("claim candidates behind the locked head command").isEmpty();
            }
        }

        @Test
        @DisplayName("Even when the referenced reservation row is locked, the post candidate carries the reservation's state — so it does not halt as if the reservation were missing (F10)")
        void aLockedReservationIsStillReported() {
            // given: hold-1 ended as applied, and the hold-1 row is locked because another transaction is writing it
            inserted(newReserve("hold-1", "order-1", 1));
            inserted(newPost("capture-1", "order-1", 2, "hold-1"));
            finished("hold-1", appliedRow(0));
            long hold1 = record("hold-1").rowId();
            try (OtherTransaction<Boolean> writer = OtherTransaction.start(storage.transactions(),
                    () -> store.compareAndSet(new RowChange(hold1, StoredState.APPLIED, 0, appliedRow(0),
                            new RowChange.Stamp.Completion())))) {
                writer.awaitWork();

                // when
                List<DueCommand> due = inTx(() -> lockCandidates(10));

                // then
                assertThat(due).as("number of candidates").hasSize(1);
                assertThat(due.get(0).reservation()).as("locked reservation as read")
                        .contains(new QueuedCommand("hold-1", 1, StoredState.APPLIED));
            }
        }

        @Test
        @DisplayName("The next seq waits until the transaction that took a seq of the same key commits — so seq order equals commit order")
        void theKeyLockHoldsUntilCommit() {
            // given: the first acceptance took seq 1 of order-1 and has not committed yet
            try (OtherTransaction<Long> first = OtherTransaction.start(storage.transactions(),
                    () -> keys.lockKeyAndTakeSeq("order-1"));
                 OtherTransaction<Long> second = startAfter(first, () -> keys.lockKeyAndTakeSeq("order-1"))) {

                // when: the second one is blocked until the first one commits
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();

                // then
                assertThat(second.awaitWork()).as("seq taken by the second acceptance").isEqualTo(2);
            }
        }

        @Test
        @DisplayName("When the same commandId arrives concurrently, the later one gets back the row committed first, and its transaction stays usable (G7)")
        void aConcurrentDuplicateLeavesTheAppTransactionUsable() {
            // given: the first acceptance inserted pay-1 and has not committed yet
            try (OtherTransaction<Optional<CommandRecord>> first = OtherTransaction.start(storage.transactions(),
                    () -> store.insertIfAbsent(newTransfer("pay-1", "order-1", 1)));
                 OtherTransaction<String> second = startAfter(first, () -> {
                     Optional<CommandRecord> existing = store.insertIfAbsent(newTransfer("pay-1", "order-2", 1));
                     long nextOnAnotherKey = keys.lockKeyAndTakeSeq("order-3"); // succeeds only if the transaction is still alive
                     return existing.map(CommandRecord::orderingKey).orElse("-") + "/" + nextOnAnotherKey;
                 })) {

                // when: the later one is blocked until the first inserter commits
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();

                // then
                assertThat(second.awaitWork()).as("ordering key of the row the later one got back / seq it took next")
                        .isEqualTo("order-1/1");
            }
        }

        /** Starts the next transaction after the earlier one finished its work (after it took its locks). */
        private <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, Supplier<T> work) {
            earlier.awaitWork();
            return OtherTransaction.start(storage.transactions(), work);
        }
    }

    @Nested
    @Order(8)
    @DisplayName("When a command halts — operator port")
    class HaltRecord {

        @Test
        @DisplayName("The halt reason and time are recorded")
        void haltingRecordsWhyAndWhen() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));

            // when
            halted("pay-1");

            // then
            HaltMark halt = record("pay-1").halt().orElseThrow(() -> new AssertionError("no halt record"));
            assertThat(halt.reason()).as("halt reason").isEqualTo(HaltReason.RESERVATION_NOT_EARLIER);
            assertThat(halt.haltedAt()).as("halt time").isNotNull();
        }

        @Test
        @DisplayName("Ending by abandon clears the halt record")
        void abandoningClearsTheHaltRecord() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            halted("pay-1");

            // when
            finished("pay-1", new CommandRow(StoredState.SKIPPED, 1, 0, Optional.empty(), Optional.empty(), 0,
                    Optional.of(SkipReason.ABANDONED_BY_OPERATOR)));

            // then
            assertThat(record("pay-1").halt()).as("halt record").isEmpty();
        }
    }

    @Nested
    @Order(9)
    @DisplayName("When halted commands are listed — operator port (O5)")
    class FindingHalted {

        @Test
        @DisplayName("Only halted commands come out, in row order")
        void findsOnlyHaltedInRowOrder() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));
            inserted(newTransfer("pay-3", "order-3", 1));
            inserted(newTransfer("pay-4", "order-4", 1));
            halted("pay-3");
            halted("pay-1");

            // when
            List<String> found = commandIds(inTx(() -> store.findHalted(0, 10)));

            // then
            assertThat(found).as("halted commands").containsExactly("pay-1", "pay-3");
        }

        @Test
        @DisplayName("The next page starts after the last row of the previous page — commands that halt in between are neither repeated nor missed")
        void pagingContinuesAfterTheCursorWhileNewHaltsArrive() {
            // given: three halted and the first page (2 items) was read
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));
            inserted(newTransfer("pay-3", "order-3", 1));
            inserted(newTransfer("pay-4", "order-4", 1));
            halted("pay-1");
            halted("pay-2");
            halted("pay-3");
            List<CommandRecord> first = inTx(() -> store.findHalted(0, 2));
            halted("pay-4");

            // when
            long cursor = first.get(first.size() - 1).rowId();
            List<String> second = commandIds(inTx(() -> store.findHalted(cursor, 2)));

            // then
            assertThat(commandIds(first)).as("first page").containsExactly("pay-1", "pay-2");
            assertThat(second).as("second page").containsExactly("pay-3", "pay-4");
        }

        private static List<String> commandIds(List<CommandRecord> records) {
            return records.stream().map(CommandRecord::commandId).toList();
        }
    }

    @Nested
    @Order(10)
    @DisplayName("When the backlog is counted — health")
    class CountingTheBacklog {

        @Test
        @DisplayName("Unfinished and halted commands are counted separately, with the acceptance time of the longest-waiting one")
        void backlogCountsUnfinishedAndHalted() {
            // given: two waiting, one in progress, one halted, one finished
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));
            inserted(newTransfer("pay-3", "order-3", 1));
            inserted(newTransfer("pay-4", "order-4", 1));
            inserted(newTransfer("pay-5", "order-5", 1));
            claimed("pay-3", 1);
            halted("pay-4");
            claimed("pay-5", 1);
            finished("pay-5", appliedRow(1));

            // when
            Backlog backlog = inTx(() -> store.backlog());

            // then
            assertThat(backlog.unfinished()).as("unfinished commands (halted excluded)").isEqualTo(3);
            assertThat(backlog.halted()).as("halted commands").isEqualTo(1);
            assertThat(backlog.oldestUnfinishedAcceptedAt()).as("acceptance time of the longest-waiting command")
                    .contains(record("pay-1").acceptedAt());
            assertThat(backlog.countsCapped()).as("whether the cap was reached").isFalse();
        }

        @Test
        @DisplayName("An empty store has no longest-waiting command")
        void anEmptyStoreHasNoOldest() {
            // when
            Backlog backlog = inTx(() -> store.backlog());

            // then
            assertThat(backlog).as("backlog").isEqualTo(new Backlog(0, 0, Optional.empty(), false));
        }
    }

    // ── Words that build rows: values are not hidden. Transfer ids start at 101, hashes are "hash-of-<id>" ─────────

    protected static NewCommand newTransfer(String commandId, String orderingKey, long seq) {
        return new NewCommand(commandId, orderingKey, seq, Optional.empty(), TestTransferIds.transferIdFor(orderingKey, seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    protected static NewCommand newReserve(String commandId, String orderingKey, long seq) {
        return new NewCommand(commandId, orderingKey, seq, Optional.empty(), TestTransferIds.transferIdFor(orderingKey, seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    /** The reservation's transfer id is 101, assuming the reservation went in with seq 1. */
    protected static NewCommand newPost(String commandId, String orderingKey, long seq, String reservationCommandId) {
        return new NewCommand(commandId, orderingKey, seq, Optional.of(reservationCommandId),
                TestTransferIds.transferIdFor(orderingKey, seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(BigInteger.valueOf(101)), 0, 0));
    }

    // ── Words for preconditions and checks ────────────────────────────────────────────

    /**
     * Locks candidates the way a claim does: first advances heads held by finished commands, then locks the candidates.
     * Call it inside an open transaction.
     */
    protected List<DueCommand> lockCandidates(int limit) {
        keys.passOnFinishedHeads(limit);
        return store.lockDue(limit);
    }

    protected <T> T inTx(Supplier<T> work) {
        return storage.transactions().inTransaction(work);
    }

    protected void inTx(Runnable work) {
        storage.transactions().inTransaction(() -> {
            work.run();
            return null;
        });
    }

    /** Precondition: this row is newly inserted. */
    protected void inserted(NewCommand command) {
        if (inTx(() -> store.insertIfAbsent(command)).isPresent()) {
            throw new IllegalStateException("given setup failed: " + command.commandId() + " already existed");
        }
    }

    protected CommandRecord record(String commandId) {
        return inTx(() -> store.find(commandId)).orElseThrow(
                () -> new AssertionError("Expected " + commandId + " to be stored but did not find it"));
    }

    protected static CommandRow pendingRow(long token) {
        return new CommandRow(StoredState.PENDING, token, 0, Optional.empty(), Optional.empty(), 0);
    }

    protected static CommandRow claimedRow(long token) {
        return new CommandRow(StoredState.CLAIMED, token, 0, Optional.empty(), Optional.empty(), 0);
    }

    protected static CommandRow appliedRow(long token) {
        return new CommandRow(StoredState.APPLIED, token, 0, Optional.empty(), Optional.of(LedgerResultCode.Created), 0);
    }

    protected static CommandRow rejectedRow(long token) {
        return new CommandRow(StoredState.REJECTED, token, 0, Optional.empty(),
                Optional.of(LedgerResultCode.ExceedsCredits), 0);
    }

    /** Precondition: this row becomes in progress with token token, and its lease lives for LEASE from now. */
    protected void claimed(String commandId, long token) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), r.row().state(), r.row().leaseToken(), claimedRow(token),
                new RowChange.Stamp.LeaseFor(LEASE)));
    }

    /**
     * Precondition: this accepted row becomes halted (HALTED) with token 1. The reason is a reservation order
     * violation.
     */
    protected void halted(String commandId) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), StoredState.PENDING, r.row().leaseToken(),
                new CommandRow(StoredState.HALTED, r.row().leaseToken() + 1, 0, Optional.empty(), Optional.empty(), 0),
                new RowChange.Stamp.Halt(HaltReason.RESERVATION_NOT_EARLIER)));
    }

    /** Precondition: this row ends as finalRow. */
    protected void finished(String commandId, CommandRow finalRow) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), r.row().state(), r.row().leaseToken(), finalRow,
                new RowChange.Stamp.Completion()));
    }

    /** Precondition: this in-progress row returns to accepted and waits for BACKOFF. */
    protected void released(String commandId) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), StoredState.CLAIMED, r.row().leaseToken(), pendingRow(r.row().leaseToken()),
                new RowChange.Stamp.RetryAfter(BACKOFF)));
    }

    private void changed(RowChange change) {
        if (!inTx(() -> store.compareAndSet(change))) {
            throw new IllegalStateException("given setup failed: " + change + " did not take effect");
        }
    }

    protected void assertDue(String... commandIds) {
        assertDueWithLimit(10, commandIds);
    }

    protected void assertDueWithLimit(int limit, String... commandIds) {
        assertThat(dueIds(limit)).as("claim candidates (in sendable order, limit %d)", limit).containsExactly(commandIds);
    }

    protected void assertNothingDue() {
        assertThat(dueIds(10)).as("expected no claim candidates").isEmpty();
    }

    private List<String> dueIds(int limit) {
        return inTx(() -> lockCandidates(limit)).stream().map(due -> due.command().commandId()).toList();
    }

    private void assertUnchanged(boolean changed, CommandRow expectedRow) {
        assertThat(changed).as("expected no effect").isFalse();
        assertThat(record("pay-1").row()).as("unchanged row").isEqualTo(expectedRow);
    }

    /** A rollback the test throws on purpose. */
    protected static final class Rollback extends RuntimeException {
        Rollback() {
            super("The test rolls back on purpose");
        }
    }

}
