package io.github.ledgerrelay.core.testing;

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
 * 서비스 {@link CommandLifecycle}의 행동 계약(스펙 §5.1~§5.4, G1·G4~G7). 어떤 저장소 위에서 돌려도 같아야 한다.
 * 구현마다 이 클래스를 상속한 테스트 하나를 둔다. 메모리 저장소(core)와 PostgreSQL 저장소(jdbc)가 같은 장을 읽는다.
 */
public abstract class LedgerRelayContract {

    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration BACKOFF = Duration.ofSeconds(5);

    protected final RecordingHandler handler = new RecordingHandler();
    protected StorageUnderTest storage;
    protected CommandLifecycle relay;

    /** 테스트마다 비어 있는 저장소 한 벌을 만든다. */
    protected abstract StorageUnderTest createStorage();

    @BeforeEach
    protected void givenAnEmptyRelay() {
        storage = createStorage();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(BACKOFF, BACKOFF.multipliedBy(4)));
    }

    @Nested
    @Order(1)
    @DisplayName("명령을 접수하면 — §5.1")
    class Accepting {

        @Test
        @DisplayName("접수됨으로 조회되고, 생성기가 낸 TB 이체 ID가 붙는다")
        void aNewCommandIsAccepted() {
            // when
            CommandReceipt receipt = submit(transfer("pay-1", "order-1", 500));

            // then
            assertThat(receipt).isEqualTo(new CommandReceipt("pay-1", BigInteger.ONE));
            assertQueryState("pay-1", State.ACCEPTED);
            assertThat(status("pay-1").transferId()).as("조회한 이체 ID").isEqualTo(BigInteger.ONE);
        }

        @Test
        @DisplayName("같은 명령을 다시 접수하면 처음 접수증을 그대로 돌려받는다 — 이체 ID는 바뀌지 않는다")
        void acceptingTheSameCommandAgainReturnsTheFirstReceipt() {
            // given
            CommandReceipt first = submit(transfer("pay-1", "order-1", 500));

            // when
            CommandReceipt again = submit(transfer("pay-1", "order-1", 500));

            // then
            assertThat(again).isEqualTo(first);
        }

        @Test
        @DisplayName("접수한 적 없는 commandId를 조회하면 아무것도 없다")
        void anUnknownCommandIsNotFound() {
            // when
            Optional<CommandStatus> found = relay.status("never-accepted");

            // then
            assertThat(found).as("접수한 적 없는 명령의 조회 결과").isEmpty();
        }

        @Test
        @DisplayName("U+0000이 든 commandId로 조회하면 아무것도 없고, 같은 앱 트랜잭션은 계속 쓸 수 있다 (F20)")
        void aMalformedIdIsNotFoundAndTheAppTransactionStaysUsable() {
            // when: 앱 트랜잭션 안에서 외부 키로 조회한 뒤 이어서 접수한다
            Optional<CommandStatus> found = storage.transactions().inTransaction(() -> {
                Optional<CommandStatus> looked = relay.status("pay\u00001");
                relay.submit(transfer("pay-2", "order-1", 500));
                return looked;
            });

            // then
            assertThat(found).as("U+0000이 든 commandId의 조회 결과").isEmpty();
            assertThat(relay.status("pay-2")).as("같은 트랜잭션에서 이어서 접수한 명령").isPresent();
        }

        @Test
        @DisplayName("짝 없는 서로게이트가 든 commandId로 조회하면 '?'로 바뀐 다른 명령이 나오지 않는다 (F20)")
        void aLoneSurrogateDoesNotMatchAnotherCommand() {
            // given
            submit(transfer("pay-?", "order-1", 500));

            // when
            Optional<CommandStatus> found = relay.status("pay-\uD800");

            // then
            assertThat(found).as("짝 없는 서로게이트가 든 commandId의 조회 결과").isEmpty();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("500원 이체가 접수된 상태에서 같은 commandId로 다른 내용을 접수하면 — G7")
    class AcceptingOtherContentUnderTheSameId {

        CommandReceipt first;

        @BeforeEach
        void givenPay1Of500IsAccepted() {
            first = submit(transfer("pay-1", "order-1", 500));
        }

        @Test
        @DisplayName("금액만 바꾸면 내용 충돌로 거부되고, 처음 내용으로는 여전히 같은 접수증을 받는다")
        void aDifferentAmountIsAConflict() {
            // when
            ThrowingCallable acceptingAnotherAmount = () -> submit(transfer("pay-1", "order-1", 501));

            // then
            assertRejectedAsConflict(acceptingAnotherAmount, "pay-1");
            assertThat(submit(transfer("pay-1", "order-1", 500)))
                    .as("처음 내용으로 다시 접수한 접수증").isEqualTo(first);
        }

        @Test
        @DisplayName("순서 키만 바꿔도 내용 충돌로 거부된다")
        void aDifferentOrderingKeyIsAConflict() {
            // when
            ThrowingCallable acceptingUnderAnotherKey = () -> submit(transfer("pay-1", "order-2", 500));

            // then
            assertRejectedAsConflict(acceptingUnderAnotherKey, "pay-1");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("예약을 참조하는 확정·해제를 접수하면 — §5.1-3, P2")
    class AcceptingAReference {

        @BeforeEach
        void givenAReservationInOrder1() {
            submit(reserve("hold-1", "order-1", 500));
        }

        @Test
        @DisplayName("같은 키에 접수된 예약을 참조하는 확정은 접수된다")
        void aPostOfAnAcceptedReservationIsAccepted() {
            // when
            submit(post("capture-1", "order-1", "hold-1"));

            // then
            assertQueryState("capture-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("같은 키에 접수된 예약을 참조하는 해제는 접수된다")
        void aVoidOfAnAcceptedReservationIsAccepted() {
            // when
            submit(voidOf("release-1", "order-1", "hold-1"));

            // then
            assertQueryState("release-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("접수되지 않은 예약을 참조하면 거부되고, 확정은 남지 않는다")
        void aMissingReservationIsRefused() {
            // when
            ThrowingCallable referencingHold9 = () -> submit(post("capture-1", "order-1", "hold-9"));

            // then
            assertRejectedAsMissingReservation(referencingHold9, "capture-1");
            assertThat(relay.status("capture-1")).as("거부된 확정").isEmpty();
        }

        @Test
        @DisplayName("다른 순서 키의 예약을 참조하면 거부된다")
        void aReservationUnderAnotherKeyIsRefused() {
            // when
            ThrowingCallable referencingFromOrder2 = () -> submit(post("capture-1", "order-2", "hold-1"));

            // then
            assertRejectedAsMissingReservation(referencingFromOrder2, "capture-1");
        }

        @Test
        @DisplayName("예약이 아닌 이체를 참조하면 거부된다")
        void aPlainTransferIsNotAReservation() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ThrowingCallable referencingATransfer = () -> submit(post("capture-1", "order-1", "pay-1"));

            // then
            assertRejectedAsMissingReservation(referencingATransfer, "capture-1");
        }

        @Test
        @DisplayName("자기 자신을 참조하면 명령을 만들 수 없어 접수까지 가지 않는다 (P2c-9)")
        void aCommandCannotReferenceItself() {
            // when
            ThrowingCallable referencingItself = () -> submit(post("capture-1", "order-1", "capture-1"));

            // then
            assertThatThrownBy(referencingItself)
                    .as("자기 자신을 참조한 확정이 만들어지지 않기(InvalidCommandException)")
                    .isInstanceOf(InvalidCommandException.class)
                    .hasMessageContaining("자기 자신을 reservation으로 참조할 수 없습니다");
        }
    }

    @Nested
    @Order(4)
    @DisplayName("앱 트랜잭션에 묶어 접수하면 — G1")
    class WithTheAppTransaction {

        @Test
        @DisplayName("트랜잭션 밖에서 접수하면 거부되고, 아무것도 남지 않는다")
        void submittingOutsideATransactionIsRefused() {
            // when
            ThrowingCallable submittingOutside = () -> relay.submit(transfer("pay-1", "order-1", 500));

            // then
            assertThatThrownBy(submittingOutside)
                    .as("트랜잭션 밖이라 거부되기를 기대했다")
                    .isInstanceOf(storage.outsideTransactionError());
            assertThat(relay.status("pay-1")).as("거부된 명령").isEmpty();
        }

        @Test
        @DisplayName("앱 트랜잭션이 롤백되면 접수도 사라진다")
        void aRolledBackAppTransactionTakesTheCommandWithIt() {
            // when
            Runnable submitThenFail = () -> storage.transactions().inTransaction(() -> {
                relay.submit(transfer("pay-1", "order-1", 500));
                throw new AppFailure();
            });

            // then
            assertThatThrownBy(submitThenFail::run).isInstanceOf(AppFailure.class);
            assertThat(relay.status("pay-1")).as("롤백된 명령").isEmpty();
        }

        @Test
        @DisplayName("같은 트랜잭션 안에서는 방금 접수한 명령이 조회된다")
        void theSameTransactionSeesItsOwnCommand() {
            // when
            State seen = storage.transactions().inTransaction(() -> {
                relay.submit(transfer("pay-1", "order-1", 500));
                return relay.status("pay-1").map(CommandStatus::state).orElse(null);
            });

            // then
            assertThat(seen).as("같은 트랜잭션에서 본 상태").isEqualTo(State.ACCEPTED);
        }
    }

    @Nested
    @Order(5)
    @DisplayName("claim하면 — §5.2, G4")
    class Claiming {

        @Test
        @DisplayName("접수된 명령은 토큰 1과 함께 잡히고, TB에 보내기 전이라 여전히 접수됨으로 조회된다")
        void anAcceptedCommandIsClaimedWithToken1() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-1");
            ClaimedCommand claimed = batch.claimed().get(0);
            assertThat(claimed.leaseToken()).as("첫 claim의 토큰").isEqualTo(1);
            assertThat(claimed.skip()).as("건너뛸 대상인가").isFalse();
            assertThat(claimed.knownResult()).as("이미 아는 결과").isEmpty();
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("잡힌 명령은 TB에 보낼 이체 내용을 싣는다 — 이체 ID, 계좌, 금액, 그리고 역참조용 행 ID")
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
        @DisplayName("같은 키의 앞 명령이 끝나지 않았으면 뒤 명령은 잡히지 않는다")
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
        @DisplayName("다른 순서 키의 명령은 서로 기다리지 않는다")
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
        @DisplayName("lease가 살아 있는 동안에는 다시 잡히지 않는다")
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
        @DisplayName("lease가 끝나면 다른 워커가 다시 잡고, 토큰이 2로 오른다")
        void anExpiredLeaseIsClaimedAgainWithANewToken() {
            // given
            submit(transfer("pay-1", "order-1", 500));
            claimOnly("pay-1");
            storage.elapse(LEASE.plusSeconds(1));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-1");
            assertThat(batch.claimed().get(0).leaseToken()).as("다시 잡은 claim의 토큰").isEqualTo(2);
        }

        @Test
        @DisplayName("한 번에 limit개까지만 잡는다 — 같은 시각이면 접수한 순서대로")
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
    @DisplayName("잡은 명령에 결과를 쓰면 — §5.3")
    class WritingResults {

        ClaimedCommand pay1;

        @BeforeEach
        void givenPay1IsClaimed() {
            submit(transfer("pay-1", "order-1", 500));
            pay1 = claimOnly("pay-1");
        }

        @Test
        @DisplayName("성공(Created)을 쓰면 적용됨이 되고, 핸들러가 적용됨을 한 번 받는다")
        void successApplies() {
            // when
            WriteResult result = relay.complete(pay1, Created);

            // then
            assertWritten(result);
            assertQueryState("pay-1", State.APPLIED);
            assertHandlerRanOnceFor("pay-1", State.APPLIED);
        }

        @Test
        @DisplayName("핸들러가 받은 조회 결과에는 완료 시각이 있다 — 핸들러는 끝난 명령을 본다")
        void theHandlerSeesTheCompletionTime() {
            // when
            relay.complete(pay1, Created);

            // then
            assertThat(handler.completedFor("pay-1").get(0).completedAt()).as("핸들러가 받은 완료 시각").isPresent();
        }

        @Test
        @DisplayName("잔액 부족을 쓰면 거절됨이 되고, 사유는 업무 거절(ExceedsCredits)이다")
        void aBusinessRejectionRejects() {
            // when
            relay.complete(pay1, ExceedsCredits);

            // then
            assertQueryState("pay-1", State.REJECTED);
            assertRejection("pay-1", ResultCategory.BUSINESS, ExceedsCredits);
        }

        @Test
        @DisplayName("내용 충돌을 쓰면 충돌이 되고, 사유가 남는다")
        void aContentConflictConflicts() {
            // when
            relay.complete(pay1, ExistsWithDifferentAmount);

            // then
            assertQueryState("pay-1", State.CONFLICT);
            assertRejection("pay-1", ResultCategory.CONFLICT, ExistsWithDifferentAmount);
        }

        @Test
        @DisplayName("TB에 보내기 전에 제출을 기록하면 처리 중으로 조회된다")
        void recordingASubmissionShowsInProgress() {
            // when
            WriteResult result = relay.markSubmitted(pay1);

            // then
            assertWritten(result);
            assertQueryState("pay-1", State.IN_PROGRESS);
        }

        @Test
        @DisplayName("응답이 없어 되돌리면 backoff가 지나기 전에는 잡히지 않는다")
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
        @DisplayName("되돌린 명령은 backoff가 지나면 같은 이체 ID로 다시 잡힌다 — 미확정은 같은 ID 재제출로만 푼다")
        void aReleasedCommandIsClaimedAgainWithTheSameTransferId() {
            // given
            relay.markSubmitted(pay1);
            relay.release(pay1);
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("pay-1");

            // then
            assertThat(again.submission().transferId()).as("다시 잡은 명령의 이체 ID").isEqualTo(BigInteger.ONE);
            assertThat(again.leaseToken()).as("다시 잡은 claim의 토큰").isEqualTo(2);
        }

        @Test
        @DisplayName("한 번 보낸 뒤 되돌린 명령은 처리 중으로 조회된다 — 미확정이 접수됨으로 돌아가지 않는다")
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
    @DisplayName("lease가 끝나 다른 워커가 잡은 뒤 앞 워커가 쓰면 — G5, E3")
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
        @DisplayName("앞 워커의 결과 쓰기는 0행이고, 핸들러는 실행되지 않으며, 명령은 처리 중 그대로다")
        void theLateResultIsFenced() {
            // when
            WriteResult result = relay.complete(byWorker1, Created);

            // then
            assertFenced(result);
            assertHandlerNeverRanFor("pay-1");
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("앞 워커의 제출 기록도 0행이다")
        void theLateSubmissionIsFenced() {
            // when
            WriteResult result = relay.markSubmitted(byWorker1);

            // then
            assertFenced(result);
            assertQueryState("pay-1", State.ACCEPTED);
        }

        @Test
        @DisplayName("새 워커의 결과는 반영되고, 핸들러는 한 번만 실행된다")
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
        @DisplayName("이미 끝난 명령에 같은 토큰으로 다시 써도 0행이고, 핸들러는 다시 실행되지 않는다")
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
        @DisplayName("없는 명령에 쓰면 0행이다")
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
    @DisplayName("결과 반영 핸들러가 실패하면 — §5.4, G6")
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
        @DisplayName("핸들러 실패가 결과로 돌아오고, 결과 쓰기는 취소되어 명령은 처리 중으로 남는다")
        void theResultIsRolledBackWithTheHandler() {
            // when
            WriteResult result = relay.complete(pay1, ExceedsCredits);

            // then
            assertHandlerFailed(result);
            assertQueryState("pay-1", State.IN_PROGRESS);
            assertThat(status("pay-1").rejection()).as("취소된 결과의 거절 사유").isEmpty();
            assertHandlerNeverRanFor("pay-1");
        }

        @Test
        @DisplayName("backoff가 지나기 전에는 다시 잡히지 않는다")
        void theFailedCommandWaitsForItsBackoff() {
            // given
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF.dividedBy(2));

            // then
            assertNothingClaimed(relay.claim(10, LEASE));
        }

        @Test
        @DisplayName("backoff 뒤 다시 잡히면 받았던 TB 결과를 싣고 온다 — TB에 다시 보내지 않게")
        void theKnownResultComesBackWithTheNextClaim() {
            // given
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("pay-1");

            // then
            assertThat(again.knownResult()).as("다시 잡은 명령이 싣고 온 결과").contains(ExceedsCredits);
        }

        @Test
        @DisplayName("다시 실패하면 두 배를 기다린다 — 핸들러 실패 횟수가 남아 있어야 대기가 는다")
        void aSecondFailureWaitsTwiceAsLong() {
            // given
            handler.failNextCalls(2);
            relay.complete(pay1, ExceedsCredits);
            storage.elapse(BACKOFF);
            ClaimedCommand again = claimOnly("pay-1");
            assertHandlerFailed(relay.complete(again, ExceedsCredits));

            // when
            // PG의 now()는 흐르므로 경계에 붙이지 않고 대기의 절반씩 지나 본다
            storage.elapse(BACKOFF);
            ClaimBatch tooEarly = relay.claim(10, LEASE);
            storage.elapse(BACKOFF);
            ClaimBatch onTime = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(tooEarly);
            assertClaimed(onTime, "pay-1");
        }

        @Test
        @DisplayName("다시 잡아 같은 결과로 끝내면 핸들러가 한 번 실행되고 거절됨으로 끝난다")
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
    @DisplayName("결과 반영 핸들러가 Error를 던지면 — §5.4, G6")
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
        @DisplayName("Error는 삼키지 않고 그대로 던진다 — JVM 수준 문제를 숨기지 않는다")
        void theErrorIsRethrown() {
            // when
            ThrowingCallable completing = () -> relay.complete(pay1, ExceedsCredits);

            // then
            assertThatThrownBy(completing)
                    .as("핸들러가 던진 Error가 그대로 나오기를 기대했다")
                    .isInstanceOf(RecordingHandler.HandlerCrash.class);
        }

        @Test
        @DisplayName("결과 쓰기는 취소되어, 핸들러 효과 없이 끝난 것으로 남지 않는다")
        void theResultIsRolledBack() {
            // when
            catchThrowable(() -> relay.complete(pay1, ExceedsCredits));

            // then
            assertQueryState("pay-1", State.IN_PROGRESS);
            assertHandlerNeverRanFor("pay-1");
        }

        @Test
        @DisplayName("받은 TB 결과는 남아 backoff 뒤 다시 잡힐 때 싣고 온다 — TB에 다시 보내 원래 사유를 잃지 않게")
        void theKnownResultIsKept() {
            // given
            catchThrowable(() -> relay.complete(pay1, ExceedsCredits));
            storage.elapse(BACKOFF);

            // when
            ClaimedCommand again = claimOnly("pay-1");

            // then
            assertThat(again.knownResult()).as("다시 잡은 명령이 싣고 온 결과").contains(ExceedsCredits);
        }
    }

    @Nested
    @Order(10)
    @DisplayName("같은 키의 앞 명령이 끝나면 — G4")
    class AfterTheEarlierCommandFinishes {

        @Test
        @DisplayName("뒤 명령이 잡힌다")
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
        @DisplayName("예약이 적용됐으면 확정은 예약의 이체 ID를 pending ID로 싣고 TB로 간다")
        void aPostOfAnAppliedReservationGoesToTigerBeetle() {
            // given
            submit(reserve("hold-1", "order-1", 500));
            submit(post("capture-1", "order-1", "hold-1"));
            finished("hold-1", Created);

            // when
            ClaimedCommand capture = claimOnly("capture-1");

            // then
            assertThat(capture.skip()).as("건너뛸 대상인가").isFalse();
            assertThat(capture.submission().body().pendingTransferId()).as("확정이 가리키는 예약 이체 ID")
                    .contains(BigInteger.ONE);
        }

        @Test
        @DisplayName("예약이 거절됐으면 확정은 건너뛸 대상으로 잡힌다")
        void aPostOfARejectedReservationIsClaimedToBeSkipped() {
            // given
            submit(reserve("hold-1", "order-1", 500));
            submit(post("capture-1", "order-1", "hold-1"));
            finished("hold-1", ExceedsCredits);

            // when
            ClaimedCommand capture = claimOnly("capture-1");

            // then
            assertThat(capture.skip()).as("건너뛸 대상인가").isTrue();
        }

        @Test
        @DisplayName("건너뛰면 건너뜀으로 끝나고, 거절 사유 없이 '예약 미적용' 이유로 핸들러가 한 번 실행된다")
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
            assertThat(status("capture-1").rejection()).as("건너뜀의 거절 사유").isEmpty();
            assertThat(status("capture-1").skipReason()).as("건너뛴 이유").contains(SkipReason.RESERVATION_NOT_APPLIED);
            assertHandlerRanOnceFor("capture-1", State.SKIPPED);
        }

        @Test
        @DisplayName("건너뜀의 핸들러가 실패하면 backoff 뒤 다시 건너뛸 대상으로 잡힌다 — 아는 결과 없이")
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
            assertThat(again.skip()).as("다시 건너뛸 대상인가").isTrue();
            assertThat(again.knownResult()).as("건너뜀이 남긴 결과").isEmpty();
        }
    }

    @Nested
    @Order(11)
    @DisplayName("워커 단계를 앱 트랜잭션 안에서 부르면 — G6")
    class WorkerStepsInsideATransaction {

        @Test
        @DisplayName("claim은 거부되고, 아무것도 잡히지 않는다")
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
        @DisplayName("결과 쓰기는 거부되고, 핸들러가 실패해도 결과가 반쯤 커밋되지 않는다")
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
        @DisplayName("제출 기록은 거부되고, 처리 중이 되지 않는다")
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
        @DisplayName("되돌리기는 거부된다")
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
        @DisplayName("건너뛰기는 거부되고, 핸들러가 실행되지 않는다 — 건너뛰기도 핸들러를 부르므로 G6이 같다")
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
    @DisplayName("확정이 참조한 예약 행이 없는 상태에서 — F10")
    class WhenTheReservationRowIsMissing {

        @BeforeEach
        void givenCapture1WhoseHold1RowIsMissing() {
            acceptedPastTheChecks(postRow("capture-1", "order-1", 2, "hold-1", 101));
        }

        @Test
        @DisplayName("claim하면 TB에 보내지 않고 멈춘 명령으로 알린다 — 예약이 충돌로 끝났다면 같은 TB ID에 남의 이체가 있다")
        void claimingHaltsIt() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("claim하면 행이 멈춤이 되고 토큰이 1 오른다")
        void haltingParksTheRow() {
            // when
            relay.claim(10, LEASE);

            // then
            assertRow("capture-1", haltedRow(1));
        }
    }

    @Nested
    @Order(13)
    @DisplayName("같은 키에서 확정이 자기가 참조한 예약보다 앞 순번인 상태에서 — F9")
    class WhenThePostIsAheadOfItsReservation {

        @BeforeEach
        void givenCapture1AtSeq1AheadOfHold1AtSeq2() {
            acceptedPastTheChecks(reserveRow("hold-1", "order-1", 2));
            acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102));
        }

        @Test
        @DisplayName("claim하면 잡지 않고 멈춘 명령으로 알린다 — 먼저 보내면 잘못 거절되고 예약 금액이 묶인다")
        void claimingHaltsIt() {
            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertHalted(batch, "capture-1");
            assertNothingClaimed(batch);
        }

        @Test
        @DisplayName("claim하면 행이 멈춤이 되고 토큰이 1 오른다 — TB에는 보내지 않았다")
        void haltingParksTheRow() {
            // when
            relay.claim(10, LEASE);

            // then
            assertRow("capture-1", haltedRow(1));
        }

        @Test
        @DisplayName("조회하면 멈춤으로 보인다 — 접수됨으로 보여 원인을 놓치지 않게")
        void theQueryShowsItHalted() {
            // when
            relay.claim(10, LEASE);

            // then
            assertQueryState("capture-1", State.HALTED);
        }

        @Test
        @DisplayName("멈춘 명령은 다음 claim에서 다시 알리지 않는다 — 알림은 멈출 때 한 번이다")
        void itIsReportedOnce() {
            // given
            relay.claim(10, LEASE);

            // when
            ClaimBatch next = relay.claim(10, LEASE);

            // then
            assertNothingHalted(next);
        }

        @Test
        @DisplayName("멈춘 키의 뒤 명령은 운영자가 풀 때까지 기다린다 — 같은 키의 순서를 건너뛰지 않는다(G4)")
        void laterCommandsInTheKeyWait() {
            // given
            relay.claim(10, LEASE);

            // when
            ClaimBatch next = relay.claim(10, LEASE);

            // then
            assertNothingClaimed(next);
        }

        @Test
        @DisplayName("다른 순서 키의 명령은 멈춘 명령과 상관없이 잡힌다")
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
        @DisplayName("한 번에 하나만 잡아도 멈춘 뒤에는 다른 키의 명령이 잡힌다 — 멈춘 명령은 limit을 차지하지 않는다")
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
    @DisplayName("멈춘 확정을 운영자가 포기하면 — F9")
    class AbandoningAHaltedPost {

        @BeforeEach
        void givenCapture1IsHaltedAheadOfHold1() {
            acceptedPastTheChecks(reserveRow("hold-1", "order-1", 2));
            acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102));
            relay.claim(10, LEASE);
        }

        @Test
        @DisplayName("건너뜀으로 끝나고, 핸들러가 '운영자 포기' 이유를 한 번 받는다 — TB에는 한 번도 가지 않았다")
        void itEndsSkippedAsAbandoned() {
            // when
            WriteResult result = relay.abandonHalted("capture-1");

            // then
            assertWritten(result);
            assertHandlerRanOnceAsAbandoned("capture-1");
            assertThat(status("capture-1").submitCount()).as("TB에 보낸 횟수").isZero();
        }

        @Test
        @DisplayName("키가 풀려 뒤에 있던 예약이 잡힌다")
        void theKeyIsReleased() {
            // given
            relay.abandonHalted("capture-1");

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "hold-1");
        }

        @Test
        @DisplayName("핸들러가 실패하면 멈춘 그대로 남고, 핸들러 실패가 돌아온다 — 운영자가 다시 포기할 수 있다")
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
        @DisplayName("핸들러가 Error를 던지면 Error는 그대로 나오고, 멈춘 그대로 남는다")
        void aCrashingHandlerLeavesItHalted() {
            // given
            handler.crashNextCall();

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("capture-1");

            // then
            assertThatThrownBy(abandoning).as("핸들러가 던진 Error가 그대로 나오기를 기대했다")
                    .isInstanceOf(RecordingHandler.HandlerCrash.class);
            assertRow("capture-1", haltedRow(1));
        }
    }

    @Nested
    @Order(15)
    @DisplayName("멈추지 않은 명령을 포기하려 하면")
    class AbandoningSomethingElse {

        @Test
        @DisplayName("접수된 명령이면 거부되고, 아무것도 바뀌지 않는다")
        void anAcceptedCommandIsRefused() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("pay-1");

            // then
            assertThatThrownBy(abandoning).as("멈춘 명령이 아니라 거부되기를 기대했다")
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("HALTED");
            assertRow("pay-1", CommandRow.accepted());
        }

        @Test
        @DisplayName("접수한 적 없는 명령이면 거부된다")
        void anUnknownCommandIsRefused() {
            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("never-accepted");

            // then
            assertThatThrownBy(abandoning).as("없는 명령이라 거부되기를 기대했다")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never-accepted");
        }

        @Test
        @DisplayName("짝 없는 서로게이트가 든 commandId면 접수한 적 없는 명령으로 거부되고, '?'로 바뀐 다른 명령은 그대로다 (F20)")
        void aLoneSurrogateIdIsRefusedAsUnknown() {
            // given
            submit(transfer("pay-?", "order-1", 500));

            // when
            ThrowingCallable abandoning = () -> relay.abandonHalted("pay-\uD800");

            // then
            assertThatThrownBy(abandoning).as("접수될 수 없는 id라 없는 명령으로 거부되기를 기대했다")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("접수한 적 없는 명령");
            assertRow("pay-?", CommandRow.accepted());
        }

        @Test
        @DisplayName("트랜잭션 안에서 부르면 거부된다 — 핸들러 실패가 바깥 트랜잭션에 묻히지 않게(G6)")
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
    @DisplayName("데이터가 어긋난 행이 claim 후보가 되면 — F29")
    class AMalformedRowIsACandidate {

        @Test
        @DisplayName("그 행만 멈춤으로 알리고, 다른 키의 명령은 그대로 잡힌다 — 한 행의 이상이 릴레이 전체를 멈추지 않게")
        void aMalformedRowIsHaltedAndOtherKeysKeepFlowing() {
            assumeTrue(storage.allowsMalformedRows(), "이 저장소는 스키마가 이런 행을 막는다(F31)");
            // given: 참조 예약이 비어 있는 확정 행(접수 검사로는 만들 수 없다)과, 다른 키의 정상 이체
            acceptedPastTheChecks(new NewCommand("capture-1", "order-1", 1, Optional.empty(), BigInteger.valueOf(101),
                    "hash-of-capture-1", new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO,
                            BigInteger.valueOf(500), 0, 0, 0, Optional.empty(), 0, 0)));
            submit(transfer("pay-2", "order-2", 500));

            // when
            ClaimBatch batch = relay.claim(10, LEASE);

            // then
            assertClaimed(batch, "pay-2");
            assertHalted(batch, "capture-1");
            assertThat(batch.problems()).as("문제가 있던 후보의 이유").singleElement()
                    .satisfies(problem -> assertThat(problem.halted()).as("멈춤으로 세웠는가").isTrue());
        }
    }

    @Nested
    @Order(20)
    @DisplayName("잡은 명령의 행이 지워지고 같은 commandId가 다시 접수되면 — G5")
    class TheRowWasReplaced {

        @Test
        @DisplayName("앞서 잡은 쪽이 쓰면 0행이다 — 같은 토큰을 가진 새 행에 쓰지 않는다")
        void aWriteForTheOldRowIsFencedEvenIfTheNewRowHasTheSameToken() {
            // given: pay-1을 토큰 1로 잡은 뒤 행이 지워졌고, 다시 접수된 pay-1도 토큰 1로 잡혔다
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand old = claimOnly("pay-1");
            storage.deleteCommand("pay-1");
            submit(transfer("pay-1", "order-1", 500));
            ClaimedCommand renewed = claimOnly("pay-1");
            if (renewed.leaseToken() != old.leaseToken() || renewed.rowId() == old.rowId()) {
                throw new IllegalStateException("given 준비 실패: 같은 토큰의 새 행이 아니다: " + old + " / " + renewed);
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
    @DisplayName("보낸 명령을 되돌리기를 거듭하면 — §5.3-4, backoff")
    class ReleasingAgainAndAgain {

        @Test
        @DisplayName("두 번째 되돌림 뒤에는 대기가 두 배가 되어, 처음 대기만큼 지나서는 다시 잡히지 않는다")
        void theWaitGrowsWithEachRelease() {
            // given: 보내고 되돌리기를 두 번 했다(첫 대기 BACKOFF, 둘째 대기 2×BACKOFF)
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
    @DisplayName("확정이 끝난 뒤 참조 예약 행이 사라진 상태에서 같은 확정을 다시 접수하면 — F28")
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
        @DisplayName("같은 내용이면 처음 받은 접수증이 돌아온다 — 참조 검사보다 재전송 판정이 먼저다")
        void resubmittingAFinishedPostAfterItsReservationIsGoneReturnsTheReceipt() {
            // when
            CommandReceipt again = submit(post("capture-1", "order-1", "hold-1"));

            // then
            assertThat(again).as("다시 받은 접수증").isEqualTo(original);
        }

        @Test
        @DisplayName("내용이 다르면 예약 없음이 아니라 내용 충돌로 거부된다 (G7)")
        void resubmittingItWithOtherContentIsAConflict() {
            // when
            ThrowingCallable resubmitting = () -> submit(LedgerCommand.post("capture-1").orderingKey("order-1")
                    .reservation("hold-1").amount(300).build());

            // then
            assertThatThrownBy(resubmitting).as("같은 commandId·다른 내용의 재전송")
                    .isInstanceOf(CommandConflictException.class);
        }
    }

    @Nested
    @Order(17)
    @DisplayName("이미 TB에 보낸 뒤 멈춘 명령을 포기하려 하면 — F27")
    class AbandoningAHaltedCommandThatWasSent {

        @BeforeEach
        void givenHold1IsApplied() {
            submit(reserve("hold-1", "order-1", 500));
            relay.complete(claimOnly("hold-1"), Created);
            submit(post("capture-1", "order-1", "hold-1"));
        }

        @Test
        @DisplayName("한 번 보내고 되돌린 확정이면 거부되고, 멈춘 그대로이며 핸들러는 실행되지 않는다 — TB에서는 확정됐을 수 있다")
        void aReleasedCommandIsRefused() {
            // given: capture-1을 보냈다가 되돌렸고, 그 뒤 참조 예약 행이 사라져 멈췄다
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
        @DisplayName("TB 결과를 알고 있는 확정이면 거부된다 — 핸들러만 실패했고 TB에는 기록됐다")
        void aCommandWithAKnownResultIsRefused() {
            // given: capture-1이 TB에 기록됐지만 핸들러가 실패했고, 그 뒤 참조 예약 행이 사라져 멈췄다
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
                throw new IllegalStateException("given 준비 실패: " + commandId + "가 멈추지 않았다: " + batch);
            }
        }

        private void assertRefusedAsAlreadySent(ThrowingCallable abandoning) {
            assertThatThrownBy(abandoning).as("이미 TB에 보낸 명령의 포기가 거부되기")
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미 TB에 보낸 적이 있어");
        }
    }

    @Nested
    @Order(16)
    @DisplayName("같은 앱 트랜잭션에서 예약을 접수하고 이어서 그 예약을 참조하면 — §5.1-3, P2b-1")
    class ReferencingAReservationOfTheSameTransaction {

        @Test
        @DisplayName("확정은 거부된다 — 참조하는 예약은 이미 커밋되어 있어야 한다")
        void thePostIsRefused() {
            // when
            ThrowingCallable postInTheSameTransaction = () -> storage.transactions().inTransaction(() -> {
                relay.submit(reserve("hold-1", "order-1", 500));
                return relay.submit(post("capture-1", "order-1", "hold-1"));
            });

            // then
            assertRejectedAsMissingReservation(postInTheSameTransaction, "capture-1");
            assertThat(relay.status("capture-1")).as("거부된 확정").isEmpty();
        }

        @Test
        @DisplayName("예약이 커밋된 뒤 다음 트랜잭션에서 접수하면 받아들인다")
        void aLaterTransactionMayReferenceIt() {
            // given
            submit(reserve("hold-1", "order-1", 500));

            // when
            submit(post("capture-1", "order-1", "hold-1"));

            // then
            assertQueryState("capture-1", State.ACCEPTED);
        }
    }

    // ── 명령을 만드는 말: 금액과 키는 숨기지 않는다 ──────────────────────

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

    // ── 접수 검사를 거치지 않은 행: 버그·DB 직접 수정 뒤의 상태를 만든다. 이체 ID는 100+순번이다 ──────

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

    // ── 전제와 확인의 말 ────────────────────────────────────────────

    /** 앱이 하듯 트랜잭션 안에서 접수한다. */
    protected CommandReceipt submit(LedgerCommand command) {
        return storage.transactions().inTransaction(() -> relay.submit(command));
    }

    protected CommandStatus status(String commandId) {
        return relay.status(commandId).orElseThrow(
                () -> new AssertionError(commandId + "가 접수되어 있기를 기대했지만 조회되지 않음"));
    }

    protected void assertQueryState(String commandId, State expected) {
        assertThat(status(commandId).state()).as("%s의 조회 상태", commandId).isEqualTo(expected);
    }

    protected static void assertRejectedAsConflict(ThrowingCallable accepting, String commandId) {
        assertThatThrownBy(accepting)
                .as("%s가 내용 충돌로 거부되기를 기대했다", commandId)
                .isInstanceOf(CommandConflictException.class)
                .satisfies(e -> assertThat(((CommandConflictException) e).commandId()).isEqualTo(commandId));
    }

    protected static void assertRejectedAsMissingReservation(ThrowingCallable accepting, String commandId) {
        assertThatThrownBy(accepting)
                .as("%s가 참조한 예약을 찾지 못해 거부되기를 기대했다", commandId)
                .isInstanceOf(ReservationNotFoundException.class)
                .satisfies(e -> assertThat(((ReservationNotFoundException) e).commandId()).isEqualTo(commandId));
    }

    /** 전제: 이 명령 하나만 잡힌다. 아니면 given 준비 실패로 멈춘다. */
    protected ClaimedCommand claimOnly(String commandId) {
        ClaimBatch batch = relay.claim(10, LEASE);
        if (batch.claimed().size() != 1 || !batch.claimed().get(0).commandId().equals(commandId)) {
            throw new IllegalStateException("given 준비 실패: " + commandId + " 하나만 잡히기를 기대했지만 실제로는 "
                    + idsOf(batch));
        }
        return batch.claimed().get(0);
    }

    protected static void assertClaimed(ClaimBatch batch, String... commandIds) {
        assertThat(idsOf(batch)).as("잡힌 명령(보낼 수 있게 된 순서)").isEqualTo(Arrays.asList(commandIds));
    }

    protected static void assertNothingClaimed(ClaimBatch batch) {
        assertThat(idsOf(batch)).as("아무것도 잡히지 않기를 기대했다").isEmpty();
    }

    private static List<String> idsOf(ClaimBatch batch) {
        return batch.claimed().stream().map(ClaimedCommand::commandId).toList();
    }

    /** 전제: 이 명령이 이 TB 결과로 끝났다. */
    protected void finished(String commandId, LedgerResultCode code) {
        ClaimedCommand claimed = claimOnly(commandId);
        if (!(relay.complete(claimed, code) instanceof WriteResult.Written)) {
            throw new IllegalStateException("given 준비 실패: " + commandId + "를 " + code + "로 끝내지 못했다");
        }
    }

    protected static void assertWritten(WriteResult result) {
        assertThat(result).as("쓰기가 반영되기를 기대했다").isEqualTo(new WriteResult.Written());
    }

    protected static void assertFenced(WriteResult result) {
        assertThat(result).as("쓰기가 0행으로 막히기를 기대했다").isEqualTo(new WriteResult.Fenced());
    }

    protected static void assertHandlerFailed(WriteResult result) {
        assertThat(result).as("핸들러 실패가 돌아오기를 기대했다").isInstanceOf(WriteResult.HandlerFailed.class);
        assertThat(((WriteResult.HandlerFailed) result).cause()).as("핸들러가 던진 예외")
                .isInstanceOf(RecordingHandler.HandlerFailure.class);
    }

    protected void assertHandlerRanOnceFor(String commandId, State state) {
        assertThat(handler.completedFor(commandId).stream().map(CommandStatus::state).toList())
                .as("%s에 대해 끝까지 실행된 핸들러 호출이 받은 상태", commandId)
                .containsExactly(state);
    }

    protected void assertHandlerNeverRanFor(String commandId) {
        assertThat(handler.completedFor(commandId)).as("%s에 대해 끝까지 실행된 핸들러 호출", commandId).isEmpty();
    }

    protected void assertRejection(String commandId, ResultCategory category, LedgerResultCode code) {
        assertThat(status(commandId).rejection()).as("%s의 거절 사유", commandId)
                .contains(new Rejection(category, code));
    }

    protected static void assertRefusedAsAWorkerStepInsideATransaction(ThrowingCallable step) {
        assertThatThrownBy(step)
                .as("워커 단계가 트랜잭션 안에서 거부되기를 기대했다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션 밖에서");
    }

    /** 전제: 접수 검사를 거치지 않고 이 행이 저장소에 바로 들어간다. */
    protected void acceptedPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + row.commandId() + "가 이미 있었다");
        }
    }

    protected void assertRow(String commandId, CommandRow expected) {
        CommandRow row = storage.transactions().inTransaction(() -> storage.store().find(commandId))
                .orElseThrow(() -> new AssertionError(commandId + "가 저장되어 있기를 기대했지만 찾지 못함")).row();
        assertThat(row).as("%s의 행", commandId).isEqualTo(expected);
    }

    protected static void assertHalted(ClaimBatch batch, String... commandIds) {
        assertThat(batch.halted()).as("멈춘 명령으로 알린 것").isEqualTo(Arrays.asList(commandIds));
    }

    protected static void assertNothingHalted(ClaimBatch batch) {
        assertThat(batch.halted()).as("멈춘 명령이 없기를 기대했다").isEmpty();
    }

    protected void assertHandlerRanOnceAsAbandoned(String commandId) {
        assertThat(handler.completedFor(commandId).stream()
                .map(outcome -> outcome.state() + "/" + outcome.skipReason().map(Enum::name).orElse("-")).toList())
                .as("%s에 대해 끝까지 실행된 핸들러 호출(상태/건너뛴 이유)", commandId)
                .containsExactly("SKIPPED/ABANDONED_BY_OPERATOR");
    }

    /** 앱 트랜잭션이 일부러 실패한다. */
    protected static final class AppFailure extends RuntimeException {
        AppFailure() {
            super("앱이 일부러 롤백한다");
        }
    }
}
