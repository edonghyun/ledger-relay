package io.github.ledgerrelay.core.testing;

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
 * {@link CommandStore} 구현이 지켜야 할 계약(스펙 §5.1~§5.4, §5.6). 저장소는 판단하지 않으므로, 이 계약은 넣기·찾기·
 * 잠그고 읽기·조건부 쓰기·트랜잭션의 의미만 다룬다. 업무 규칙은 {@code LedgerRelayContract}가 다룬다.
 * 구현마다 이 클래스를 상속한 테스트 하나를 둔다.
 */
public abstract class CommandStoreContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration BACKOFF = Duration.ofSeconds(5);
    /** "막혀 있다"를 보는 고정 관찰 시간(P2b-4, docs/testing.md §8) */
    protected static final Duration BLOCKED = Duration.ofMillis(300);

    protected StorageUnderTest storage;
    protected CommandStore store;

    /** 테스트마다 비어 있는 저장소 한 벌을 만든다. */
    protected abstract StorageUnderTest createStorage();

    @BeforeEach
    protected void givenAnEmptyStore() {
        storage = createStorage();
        store = storage.store();
    }

    @Nested
    @Order(1)
    @DisplayName("순번을 내면 — §5.1-2")
    class IssuingSequenceNumbers {

        @Test
        @DisplayName("순서 키마다 1부터 오르고, 키끼리는 서로 상관없다")
        void eachKeyCountsFromOne() {
            // when
            long first = inTx(() -> store.nextSeq("order-1"));
            long second = inTx(() -> store.nextSeq("order-1"));
            long otherKey = inTx(() -> store.nextSeq("order-2"));

            // then
            assertThat(first).as("order-1의 첫 순번").isEqualTo(1);
            assertThat(second).as("order-1의 둘째 순번").isEqualTo(2);
            assertThat(otherKey).as("order-2의 첫 순번").isEqualTo(1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("넣고 찾으면 — §5.1-4, §5.6")
    class InsertingAndFinding {

        @Test
        @DisplayName("넣은 행은 막 접수된 상태로 찾아지고, 넣은 값이 그대로 남는다")
        void anInsertedRowIsFoundAsAccepted() {
            // when
            Optional<CommandRecord> existing = inTx(() -> store.insertIfAbsent(newTransfer("pay-1", "order-1", 1)));

            // then
            assertThat(existing).as("이미 있던 행").isEmpty();
            CommandRecord found = record("pay-1");
            assertThat(found.row()).as("막 넣은 행의 상태").isEqualTo(CommandRow.accepted());
            assertThat(found.orderingKey()).as("순서 키").isEqualTo("order-1");
            assertThat(found.seq()).as("순번").isEqualTo(1);
            assertThat(found.transferId()).as("이체 ID").isEqualTo(BigInteger.valueOf(101));
            assertThat(found.contentHash()).as("내용 해시").isEqualTo("hash-of-pay-1");
            assertThat(found.body()).as("이체 본문").isEqualTo(new TransferBody(CommandKind.TRANSFER,
                    BigInteger.valueOf(1), BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0,
                    Optional.empty(), 0, 0));
            assertThat(found.completedAt()).as("완료 시각").isEmpty();
        }

        @Test
        @DisplayName("같은 commandId로 다시 넣으면 넣지 않고 처음 행을 돌려준다 — 처음 내용이 남는다")
        void aSecondInsertReturnsTheFirstRow() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));

            // when
            Optional<CommandRecord> existing = inTx(() -> store.insertIfAbsent(newTransfer("pay-1", "order-2", 1)));

            // then
            assertThat(existing.map(CommandRecord::orderingKey)).as("돌려받은 행의 순서 키").contains("order-1");
            assertThat(record("pay-1").orderingKey()).as("남아 있는 행의 순서 키").isEqualTo("order-1");
        }

        @Test
        @DisplayName("행 ID는 넣을 때마다 다르다")
        void eachRowGetsItsOwnId() {
            // when
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));

            // then
            assertThat(record("pay-1").rowId()).as("두 행의 ID").isNotEqualTo(record("pay-2").rowId());
        }

        @Test
        @DisplayName("예약을 참조하는 행은 참조와 예약의 이체 ID를 그대로 남긴다")
        void aPostKeepsItsReference() {
            // when
            inserted(newPost("capture-1", "order-1", 2, "hold-1"));

            // then
            CommandRecord found = record("capture-1");
            assertThat(found.reservationCommandId()).as("참조한 예약").contains("hold-1");
            assertThat(found.body().pendingTransferId()).as("예약의 이체 ID").contains(BigInteger.valueOf(101));
        }

        @Test
        @DisplayName("넣은 적 없는 commandId는 찾지 못한다")
        void anUnknownCommandIsNotFound() {
            // when
            Optional<CommandRecord> found = inTx(() -> store.find("never-inserted"));

            // then
            assertThat(found).as("넣은 적 없는 명령").isEmpty();
        }

        @Test
        @DisplayName("부호 없는 최댓값도 그대로 남는다 — 금액 2^128-1, user_data_64 2^64-1, ledger·user_data_32 2^32-1, code 65535")
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
            assertThat(record("hold-max").body()).as("다시 읽은 이체 본문").isEqualTo(widest);
            assertThat(record("hold-max").transferId()).as("다시 읽은 이체 ID").isEqualTo(u128Max.subtract(BigInteger.TEN));
        }

        @Test
        @DisplayName("같은 트랜잭션에서는 방금 넣은 행으로 표시되고, 커밋한 뒤에는 그렇지 않다 — P2b-2")
        void aRowIsMarkedAsAcceptedInThisTransactionUntilCommit() {
            // when
            boolean insideTheInsertingTransaction = inTx(() -> {
                store.insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                return store.find("pay-1").orElseThrow().acceptedInThisTransaction();
            });

            // then
            assertThat(insideTheInsertingTransaction).as("넣은 트랜잭션 안에서 본 표시").isTrue();
            assertThat(record("pay-1").acceptedInThisTransaction()).as("커밋한 뒤에 본 표시").isFalse();
        }
    }

    @Nested
    @Order(3)
    @DisplayName("트랜잭션을 열고 닫으면 — G1, G6")
    class TransactionBoundaries {

        @Test
        @DisplayName("던지면 그 안에서 넣은 행이 사라지고, 예외는 그대로 나온다")
        void aThrowingTransactionLeavesNoRow() {
            // when
            Runnable insertThenFail = () -> storage.transactions().inTransaction(() -> {
                store.insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                throw new Rollback();
            });

            // then
            assertThatThrownBy(insertThenFail::run).isInstanceOf(Rollback.class);
            assertThat(inTx(() -> store.find("pay-1"))).as("롤백된 행").isEmpty();
        }

        @Test
        @DisplayName("안에서 다시 열면 바깥 트랜잭션에 합류해, 바깥이 던질 때 함께 사라진다")
        void anInnerCallJoinsTheOuterTransaction() {
            // when
            Runnable nestedThenFail = () -> storage.transactions().inTransaction(() -> {
                storage.transactions().inTransaction(() -> store.insertIfAbsent(newTransfer("pay-1", "order-1", 1)));
                throw new Rollback();
            });

            // then
            assertThatThrownBy(nestedThenFail::run).isInstanceOf(Rollback.class);
            assertThat(inTx(() -> store.find("pay-1"))).as("바깥과 함께 롤백된 행").isEmpty();
        }

        @Test
        @DisplayName("밖에서 활성 트랜잭션을 요구하면 거부된다")
        void requiringATransactionOutsideOneFails() {
            // when
            ThrowingCallable requiringOutside = () -> storage.transactions().requireActive();

            // then
            assertThatThrownBy(requiringOutside)
                    .as("트랜잭션 밖이라 거부되기를 기대했다")
                    .isInstanceOf(storage.outsideTransactionError());
        }

        @Test
        @DisplayName("안에서 활성 트랜잭션을 요구하면 통과한다")
        void requiringATransactionInsideOnePasses() {
            // when
            ThrowingCallable requiringInside = () -> inTx(() -> storage.transactions().requireActive());

            // then
            assertThatCode(requiringInside).as("트랜잭션 안에서 요구한 결과").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("안에서는 활성으로, 밖에서는 활성이 아닌 것으로 보인다")
        void reportsWhetherATransactionIsActive() {
            // when
            boolean inside = inTx(() -> storage.transactions().isActive());
            boolean outside = storage.transactions().isActive();

            // then
            assertThat(inside).as("트랜잭션 안에서 본 활성 여부").isTrue();
            assertThat(outside).as("트랜잭션 밖에서 본 활성 여부").isFalse();
        }
    }

    @Nested
    @Order(4)
    @DisplayName("claim 후보를 잠그면 — §5.2")
    class LockingDueCommands {

        @Test
        @DisplayName("넣은 명령은 바로 후보다")
        void anInsertedCommandIsDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));

            // then
            assertDue("pay-1");
        }

        @Test
        @DisplayName("순서 키마다 맨 앞의 미완료 명령만 후보다")
        void onlyTheHeadOfEachKeyIsDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));

            // then
            assertDue("pay-1");
        }

        @Test
        @DisplayName("맨 앞 명령이 처리 중이면 그 키에서는 아무것도 후보가 아니다")
        void aKeyWithAClaimedHeadHasNothingDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            claimed("pay-1", 1);

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("막힌 키의 뒤 명령들은 limit을 차지하지 않는다 — 다른 키가 굶지 않게")
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
        @DisplayName("맨 앞 명령이 끝나면 다음 명령이 후보다")
        void theNextCommandIsDueOnceTheHeadFinishes() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            finished("pay-1", appliedRow(1));

            // then
            assertDue("pay-2");
        }

        @Test
        @DisplayName("끝난 명령은 후보가 아니다")
        void aFinishedCommandIsNotDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            finished("pay-1", rejectedRow(1));

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("lease가 살아 있으면 후보가 아니다")
        void aLiveLeaseIsNotDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            storage.elapse(LEASE.dividedBy(2));

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("lease가 끝나는 바로 그 시각에는 아직 후보가 아니다 — lease_expires_at < now일 때만 다시 잡는다")
        void theLeaseIsStillLiveAtItsExpiryInstant() {
            assumeTrue(storage.clockStandsStill(), "시계가 흐르는 저장소: 바로 그 시각을 만들 수 없다");
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            storage.elapse(LEASE);

            // then
            assertNothingDue();
        }

        @Test
        @DisplayName("lease가 끝나면 처리 중 명령이 다시 후보다")
        void anExpiredLeaseIsDue() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 1);
            storage.elapse(LEASE.plusSeconds(1));

            // when
            List<DueCommand> due = inTx(() -> store.lockDue(10));

            // then
            assertThat(due).as("후보 수").hasSize(1);
            assertThat(due.get(0).command().row()).as("후보의 행 — 처리 중 그대로, 토큰 1").isEqualTo(claimedRow(1));
        }

        @Test
        @DisplayName("되돌린 명령은 backoff가 지나기 전에는 후보가 아니다")
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
        @DisplayName("되돌린 명령은 backoff가 지나면 다시 후보다")
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
        @DisplayName("맨 앞 명령이 멈췄으면 그 키에서는 아무것도 후보가 아니다 — 멈춘 명령은 limit도 차지하지 않는다")
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
        @DisplayName("한 번에 limit개까지만, 접수한 순서대로 낸다")
        void atMostLimitInAcceptanceOrder() {
            // given
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-2", 1));
            inserted(newTransfer("pay-3", "order-3", 1));

            // then
            assertDueWithLimit(2, "pay-1", "pay-2");
        }

        @Test
        @DisplayName("post 후보는 참조한 예약의 순번과 상태를 싣는다 — 게이트가 볼 것")
        void aPostCarriesItsReservation() {
            // given
            inserted(newReserve("hold-1", "order-1", 1));
            inserted(newPost("capture-1", "order-1", 2, "hold-1"));
            finished("hold-1", appliedRow(1));

            // when
            List<DueCommand> due = inTx(() -> store.lockDue(10));

            // then
            assertThat(due).as("후보 수").hasSize(1);
            assertThat(due.get(0).reservation()).as("capture-1이 참조한 예약")
                    .contains(new QueuedCommand("hold-1", 1, StoredState.APPLIED));
        }

        @Test
        @DisplayName("참조한 예약 행이 없으면 예약 칸이 비어 있다 — 저장소는 참조를 검사하지 않는다")
        void aMissingReservationIsEmpty() {
            // given
            inserted(newPost("capture-1", "order-1", 1, "hold-9"));

            // when
            List<DueCommand> due = inTx(() -> store.lockDue(10));

            // then
            assertThat(due).as("후보 수").hasSize(1);
            assertThat(due.get(0).reservation()).as("없는 예약").isEmpty();
        }
    }

    @Nested
    @Order(5)
    @DisplayName("조건부로 바꾸면 — G5")
    class ComparingAndSetting {

        CommandRecord pay1;

        @BeforeEach
        void givenPay1IsHeldWithToken3() {
            inserted(newTransfer("pay-1", "order-1", 1));
            claimed("pay-1", 3);
            pay1 = record("pay-1");
        }

        @Test
        @DisplayName("상태와 토큰이 맞으면 상태 기계가 준 행으로 바뀐다")
        void aMatchingWriteLands() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(
                    new RowChange(pay1.rowId(), StoredState.CLAIMED, 3, appliedRow(3), new RowChange.Stamp.Completion())));

            // then
            assertThat(changed).as("반영 여부").isTrue();
            assertThat(record("pay-1").row()).as("바뀐 행").isEqualTo(appliedRow(3));
        }

        @Test
        @DisplayName("행의 모든 칸이 그대로 남는다 — 제출 횟수, 아는 결과, 결과 코드, 핸들러 실패 횟수")
        void everyColumnOfTheRowIsKept() {
            // given
            CommandRow failedTwiceKnowingExceedsCredits = new CommandRow(StoredState.PENDING, 3, 2,
                    Optional.of(LedgerResultCode.ExceedsCredits), Optional.of(LedgerResultCode.ExceedsCredits), 3);

            // when
            inTx(() -> store.compareAndSet(new RowChange(pay1.rowId(), StoredState.CLAIMED, 3,
                    failedTwiceKnowingExceedsCredits, new RowChange.Stamp.RetryAfter(BACKOFF))));

            // then
            assertThat(record("pay-1").row()).as("다시 읽은 행").isEqualTo(failedTwiceKnowingExceedsCredits);
        }

        @Test
        @DisplayName("건너뛴 이유도 그대로 남는다")
        void theSkipReasonIsKept() {
            // given
            CommandRow abandoned = new CommandRow(StoredState.SKIPPED, 3, 0, Optional.empty(), Optional.empty(), 0,
                    Optional.of(SkipReason.ABANDONED_BY_OPERATOR));

            // when
            finished("pay-1", abandoned);

            // then
            assertThat(record("pay-1").row()).as("다시 읽은 행").isEqualTo(abandoned);
        }

        @Test
        @DisplayName("토큰이 다르면 바뀌지 않는다")
        void anotherTokenIsRefused() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(
                    new RowChange(pay1.rowId(), StoredState.CLAIMED, 2, appliedRow(2), new RowChange.Stamp.Completion())));

            // then
            assertUnchanged(changed, claimedRow(3));
        }

        @Test
        @DisplayName("상태가 다르면 토큰이 같아도 바뀌지 않는다")
        void anotherStateIsRefused() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(
                    new RowChange(pay1.rowId(), StoredState.PENDING, 3, appliedRow(3), new RowChange.Stamp.Completion())));

            // then
            assertUnchanged(changed, claimedRow(3));
        }

        @Test
        @DisplayName("없는 행이면 바뀌지 않는다")
        void anUnknownRowIsRefused() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(new RowChange(pay1.rowId() + 1000, StoredState.CLAIMED, 3,
                    appliedRow(3), new RowChange.Stamp.Completion())));

            // then
            assertThat(changed).as("없는 행에 쓴 반영 여부").isFalse();
        }

        @Test
        @DisplayName("완료 시각을 찍으면 생기고, 그 전에는 없다")
        void completionStampsTheTime() {
            // given
            assertThat(pay1.completedAt()).as("끝나기 전의 완료 시각").isEmpty();

            // when
            finished("pay-1", appliedRow(3));

            // then
            assertThat(record("pay-1").completedAt()).as("끝난 뒤의 완료 시각").isPresent();
        }

        @Test
        @DisplayName("트랜잭션이 던지면 바꾼 것이 되돌아간다 — 핸들러가 던질 때 결과가 남지 않게(G6)")
        void aThrowingTransactionUndoesTheWrite() {
            // when
            Runnable writeThenFail = () -> storage.transactions().inTransaction(() -> {
                store.compareAndSet(new RowChange(pay1.rowId(), StoredState.CLAIMED, 3, appliedRow(3),
                        new RowChange.Stamp.Completion()));
                throw new Rollback();
            });

            // then
            assertThatThrownBy(writeThenFail::run).isInstanceOf(Rollback.class);
            assertThat(record("pay-1").row()).as("롤백 뒤의 행").isEqualTo(claimedRow(3));
            assertThat(record("pay-1").completedAt()).as("롤백 뒤의 완료 시각").isEmpty();
        }
    }

    @Nested
    @Order(6)
    @DisplayName("접수된 행을 멈춤으로 바꾸면 — F9")
    class Halting {

        @BeforeEach
        void givenPay1IsHalted() {
            inserted(newTransfer("pay-1", "order-1", 1));
            halted("pay-1");
        }

        @Test
        @DisplayName("멈춤으로 읽히고 완료 시각은 비어 있다 — 멈춘 명령은 끝난 것이 아니다")
        void aHaltedRowIsNotFinished() {
            // when
            CommandRecord found = record("pay-1");

            // then
            assertThat(found.row()).as("멈춘 행").isEqualTo(
                    new CommandRow(StoredState.HALTED, 1, 0, Optional.empty(), Optional.empty(), 0));
            assertThat(found.completedAt()).as("멈춘 행의 완료 시각").isEmpty();
        }

        @Test
        @DisplayName("운영자가 포기해 건너뜀으로 끝내면 완료 시각이 생긴다")
        void abandoningStampsTheCompletionTime() {
            // when
            boolean changed = inTx(() -> store.compareAndSet(new RowChange(record("pay-1").rowId(), StoredState.HALTED, 1,
                    new CommandRow(StoredState.SKIPPED, 1, 0, Optional.empty(), Optional.empty(), 0,
                            Optional.of(SkipReason.ABANDONED_BY_OPERATOR)),
                    new RowChange.Stamp.Completion())));

            // then
            assertThat(changed).as("반영 여부").isTrue();
            assertThat(record("pay-1").completedAt()).as("포기한 뒤의 완료 시각").isPresent();
        }
    }

    @Nested
    @Order(7)
    @DisplayName("다른 트랜잭션이 행을 잡고 있으면 — O1, R12, G4, G7")
    class WhileAnotherTransactionHoldsRows {

        @BeforeEach
        void givenAStoreWithRowLocks() {
            assumeTrue(storage.supportsRowLocks(), "행 잠금이 없는 저장소: 동시성 장을 건너뛴다(P2b-3)");
        }

        @Test
        @DisplayName("맨 앞 명령이 잡혀 있으면 그 키에서는 아무것도 후보가 아니다 — 뒤 명령이 먼저 나가지 않게(G4)")
        void aLockedHeadHidesItsWholeKey() {
            // given: 키 order-1에 pay-1(1), pay-2(2). 다른 트랜잭션이 pay-1을 후보로 잠갔다
            inserted(newTransfer("pay-1", "order-1", 1));
            inserted(newTransfer("pay-2", "order-1", 2));
            try (OtherTransaction<List<DueCommand>> worker1 = OtherTransaction.start(storage.transactions(),
                    () -> store.lockDue(10))) {
                worker1.awaitWork();

                // when: 다른 워커가 claim 후보를 찾는다
                List<DueCommand> due = inTx(() -> store.lockDue(10));

                // then
                assertThat(due).as("잡힌 맨 앞 명령 뒤의 claim 후보").isEmpty();
            }
        }

        @Test
        @DisplayName("참조한 예약 행이 잡혀 있어도 확정 후보는 예약의 상태를 싣는다 — 예약이 없다고 멈추지 않게(F10)")
        void aLockedReservationIsStillReported() {
            // given: hold-1은 적용으로 끝났고, 다른 트랜잭션이 hold-1 행을 쓰는 중이라 잠겨 있다
            inserted(newReserve("hold-1", "order-1", 1));
            inserted(newPost("capture-1", "order-1", 2, "hold-1"));
            finished("hold-1", appliedRow(0));
            long hold1 = record("hold-1").rowId();
            try (OtherTransaction<Boolean> writer = OtherTransaction.start(storage.transactions(),
                    () -> store.compareAndSet(new RowChange(hold1, StoredState.APPLIED, 0, appliedRow(0),
                            new RowChange.Stamp.Completion())))) {
                writer.awaitWork();

                // when
                List<DueCommand> due = inTx(() -> store.lockDue(10));

                // then
                assertThat(due).as("후보 수").hasSize(1);
                assertThat(due.get(0).reservation()).as("잠긴 예약을 읽은 결과")
                        .contains(new QueuedCommand("hold-1", 1, StoredState.APPLIED));
            }
        }

        @Test
        @DisplayName("같은 키의 순번을 받은 트랜잭션이 커밋할 때까지 다음 순번은 기다린다 — seq 순서가 커밋 순서와 같게(R12)")
        void theKeyLockHoldsUntilCommit() {
            // given: 첫 접수가 order-1의 순번 1을 받고 아직 커밋하지 않았다
            try (OtherTransaction<Long> first = OtherTransaction.start(storage.transactions(),
                    () -> store.nextSeq("order-1"));
                 OtherTransaction<Long> second = startAfter(first, () -> store.nextSeq("order-1"))) {

                // when: 두 번째는 첫 번째가 커밋할 때까지 막혀 있다
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();

                // then
                assertThat(second.awaitWork()).as("두 번째 접수가 받은 순번").isEqualTo(2);
            }
        }

        @Test
        @DisplayName("같은 commandId가 동시에 들어오면 늦은 쪽은 먼저 커밋된 행을 돌려받고, 그 트랜잭션은 계속 쓸 수 있다(G7)")
        void aConcurrentDuplicateLeavesTheAppTransactionUsable() {
            // given: 첫 접수가 pay-1을 넣고 아직 커밋하지 않았다
            try (OtherTransaction<Optional<CommandRecord>> first = OtherTransaction.start(storage.transactions(),
                    () -> store.insertIfAbsent(newTransfer("pay-1", "order-1", 1)));
                 OtherTransaction<String> second = startAfter(first, () -> {
                     Optional<CommandRecord> existing = store.insertIfAbsent(newTransfer("pay-1", "order-2", 1));
                     long nextOnAnotherKey = store.nextSeq("order-3"); // 트랜잭션이 살아 있어야 성공한다
                     return existing.map(CommandRecord::orderingKey).orElse("-") + "/" + nextOnAnotherKey;
                 })) {

                // when: 늦은 쪽은 먼저 넣은 쪽이 커밋할 때까지 막혀 있다
                second.assertStillWorkingAfter(BLOCKED);
                first.commit();

                // then
                assertThat(second.awaitWork()).as("늦은 쪽이 돌려받은 행의 순서 키 / 이어서 받은 순번")
                        .isEqualTo("order-1/1");
            }
        }

        /** 앞 트랜잭션이 일을 마친 뒤(잠금을 잡은 뒤) 다음 트랜잭션을 시작한다. */
        private <T> OtherTransaction<T> startAfter(OtherTransaction<?> earlier, Supplier<T> work) {
            earlier.awaitWork();
            return OtherTransaction.start(storage.transactions(), work);
        }
    }

    // ── 행을 만드는 말: 값은 숨기지 않는다. 이체 ID는 101부터, 해시는 "hash-of-<id>"다 ─────────

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

    /** 예약의 이체 ID는 예약이 순번 1로 들어갔다고 보고 101로 둔다. */
    protected static NewCommand newPost(String commandId, String orderingKey, long seq, String reservationCommandId) {
        return new NewCommand(commandId, orderingKey, seq, Optional.of(reservationCommandId),
                TestTransferIds.transferIdFor(orderingKey, seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(BigInteger.valueOf(101)), 0, 0));
    }

    // ── 전제와 확인의 말 ────────────────────────────────────────────

    protected <T> T inTx(Supplier<T> work) {
        return storage.transactions().inTransaction(work);
    }

    protected void inTx(Runnable work) {
        storage.transactions().inTransaction(() -> {
            work.run();
            return null;
        });
    }

    /** 전제: 이 행이 새로 들어간다. */
    protected void inserted(NewCommand command) {
        if (inTx(() -> store.insertIfAbsent(command)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + command.commandId() + "가 이미 있었다");
        }
    }

    protected CommandRecord record(String commandId) {
        return inTx(() -> store.find(commandId)).orElseThrow(
                () -> new AssertionError(commandId + "가 저장되어 있기를 기대했지만 찾지 못함"));
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

    /** 전제: 이 행이 토큰 token의 처리 중이 되고, lease가 지금부터 LEASE 동안 살아 있다. */
    protected void claimed(String commandId, long token) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), r.row().state(), r.row().leaseToken(), claimedRow(token),
                new RowChange.Stamp.LeaseFor(LEASE)));
    }

    /** 전제: 접수된 이 행이 토큰 1의 멈춤(HALTED)이 된다. */
    protected void halted(String commandId) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), StoredState.PENDING, r.row().leaseToken(),
                new CommandRow(StoredState.HALTED, r.row().leaseToken() + 1, 0, Optional.empty(), Optional.empty(), 0),
                new RowChange.Stamp.Halt()));
    }

    /** 전제: 이 행이 finalRow로 끝난다. */
    protected void finished(String commandId, CommandRow finalRow) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), r.row().state(), r.row().leaseToken(), finalRow,
                new RowChange.Stamp.Completion()));
    }

    /** 전제: 처리 중인 이 행이 접수 상태로 돌아가 BACKOFF 동안 기다린다. */
    protected void released(String commandId) {
        CommandRecord r = record(commandId);
        changed(new RowChange(r.rowId(), StoredState.CLAIMED, r.row().leaseToken(), pendingRow(r.row().leaseToken()),
                new RowChange.Stamp.RetryAfter(BACKOFF)));
    }

    private void changed(RowChange change) {
        if (!inTx(() -> store.compareAndSet(change))) {
            throw new IllegalStateException("given 준비 실패: " + change + "가 반영되지 않았다");
        }
    }

    protected void assertDue(String... commandIds) {
        assertDueWithLimit(10, commandIds);
    }

    protected void assertDueWithLimit(int limit, String... commandIds) {
        assertThat(dueIds(limit)).as("claim 후보(접수 순서, limit %d)", limit).containsExactly(commandIds);
    }

    protected void assertNothingDue() {
        assertThat(dueIds(10)).as("claim 후보가 없기를 기대했다").isEmpty();
    }

    private List<String> dueIds(int limit) {
        return inTx(() -> store.lockDue(limit)).stream().map(due -> due.command().commandId()).toList();
    }

    private void assertUnchanged(boolean changed, CommandRow expectedRow) {
        assertThat(changed).as("반영되지 않기를 기대했다").isFalse();
        assertThat(record("pay-1").row()).as("그대로 남은 행").isEqualTo(expectedRow);
    }

    /** 테스트가 일부러 던지는 롤백. */
    protected static final class Rollback extends RuntimeException {
        Rollback() {
            super("테스트가 일부러 롤백한다");
        }
    }

}
