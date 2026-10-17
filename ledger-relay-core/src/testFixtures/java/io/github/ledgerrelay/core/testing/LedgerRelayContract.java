package io.github.ledgerrelay.core.testing;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static io.github.ledgerrelay.core.LedgerResultCode.ExistsWithDifferentAmount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.Rejection;
import io.github.ledgerrelay.core.ReservationNotFoundException;
import io.github.ledgerrelay.core.ResultCategory;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimBatch;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.WriteResult;
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
        relay = new CommandLifecycle(storage.store(), storage.transactions(), new SequentialTransferIds(), handler,
                new Backoff(BACKOFF, BACKOFF));
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
        @DisplayName("G7: 같은 commandId에 금액만 바꿔 접수하면 내용 충돌로 거부되고, 처음 내용이 남는다")
        void aDifferentAmountUnderTheSameIdIsAConflict() {
            // given
            CommandReceipt first = submit(transfer("pay-1", "order-1", 500));

            // when
            ThrowingCallable acceptingAnotherAmount = () -> submit(transfer("pay-1", "order-1", 501));

            // then
            assertRejectedAsConflict(acceptingAnotherAmount, "pay-1");
            assertThat(submit(transfer("pay-1", "order-1", 500)))
                    .as("처음 내용으로는 여전히 같은 접수증을 받는다").isEqualTo(first);
        }

        @Test
        @DisplayName("G7: 같은 commandId에 순서 키만 바꿔도 내용 충돌이다")
        void aDifferentOrderingKeyUnderTheSameIdIsAConflict() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // then
            assertRejectedAsConflict(() -> submit(transfer("pay-1", "order-2", 500)), "pay-1");
        }

        @Test
        @DisplayName("접수한 적 없는 commandId를 조회하면 아무것도 없다")
        void anUnknownCommandIsNotFound() {
            assertThat(relay.status("never-accepted")).isEmpty();
        }
    }

    @Nested
    @Order(2)
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
            assertRejectedAsMissingReservation(() -> submit(post("capture-1", "order-1", "hold-9")), "capture-1");
            assertThat(relay.status("capture-1")).as("거부된 확정").isEmpty();
        }

        @Test
        @DisplayName("다른 순서 키의 예약을 참조하면 거부된다")
        void aReservationUnderAnotherKeyIsRefused() {
            assertRejectedAsMissingReservation(() -> submit(post("capture-1", "order-2", "hold-1")), "capture-1");
        }

        @Test
        @DisplayName("예약이 아닌 이체를 참조하면 거부된다")
        void aPlainTransferIsNotAReservation() {
            // given
            submit(transfer("pay-1", "order-1", 500));

            // then
            assertRejectedAsMissingReservation(() -> submit(post("capture-1", "order-1", "pay-1")), "capture-1");
        }

        @Test
        @DisplayName("자기 자신을 참조하면 거부된다")
        void aCommandCannotReferenceItself() {
            assertRejectedAsMissingReservation(() -> submit(post("capture-1", "order-1", "capture-1")), "capture-1");
        }
    }

    @Nested
    @Order(3)
    @DisplayName("앱 트랜잭션과 함께 — G1")
    class WithTheAppTransaction {

        @Test
        @DisplayName("트랜잭션 밖에서 접수하면 거부되고, 아무것도 남지 않는다")
        void submittingOutsideATransactionIsRefused() {
            assertThatThrownBy(() -> relay.submit(transfer("pay-1", "order-1", 500)))
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
    @Order(4)
    @DisplayName("claim하면 — §5.2")
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
        @DisplayName("G4: 같은 키의 앞 명령이 끝나지 않았으면 뒤 명령은 잡히지 않는다")
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
            storage.elapse(LEASE.minusSeconds(1));

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
        @DisplayName("한 번에 limit개까지만 잡고, 접수한 순서대로 고른다")
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
    @Order(5)
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
            storage.elapse(BACKOFF.minusSeconds(1));

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
    @Order(6)
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
    @Order(7)
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
            storage.elapse(BACKOFF.minusSeconds(1));

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
    @Order(8)
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
        @DisplayName("건너뛰면 건너뜀으로 끝나고, 사유 없이 핸들러가 한 번 실행된다")
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
        assertThat(idsOf(batch)).as("잡힌 명령(접수 순서)").isEqualTo(Arrays.asList(commandIds));
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

    /** 앱 트랜잭션이 일부러 실패한다. */
    protected static final class AppFailure extends RuntimeException {
        AppFailure() {
            super("앱이 일부러 롤백한다");
        }
    }
}
