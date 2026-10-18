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

@DisplayName("워커 한 바퀴 — 스펙 §5.3 제출과 전이, 03a")
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
    @DisplayName("접수된 이체가 있을 때 한 바퀴 돌면")
    class WithAcceptedTransfers {

        @Test
        @DisplayName("TB에 보내고 결과대로 적용됨으로 끝내며, 핸들러가 한 번 실행된다")
        void aTransferIsSubmittedAndApplied() {
            // given
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertThat(handler.completedFor("pay-1")).as("pay-1의 핸들러 실행").hasSize(1);
            assertThat(report).as("한 바퀴의 리포트").isEqualTo(new CycleReport(1, 1, 1, 0));
        }

        @Test
        @DisplayName("다른 키의 명령들은 TB 요청 하나에 접수 순서대로 담긴다")
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
        @DisplayName("TB가 잔액 부족으로 거절하면 거절됨으로 끝나고 그 코드가 남는다")
        void aRejectionEndsTheCommandRejected() {
            // given
            fake.answer(accepted(transfer("pay-1", "order-1")).transferId(), ExceedsCredits);

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.REJECTED);
            assertThat(relay.status("pay-1").orElseThrow().rejection().orElseThrow().tbCode())
                    .as("pay-1의 거절 코드").isEqualTo(ExceedsCredits);
        }

        @Test
        @DisplayName("같은 키의 명령은 맨 앞 하나만 요청에 담기고, 그것이 끝난 다음 바퀴에 뒤 명령이 간다 (G4)")
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
        @DisplayName("보낸 이체의 user_data_128에는 그 명령의 행 id가 실린다 — TB에서 PG를 역참조한다 (§5.6)")
        void theSubmissionCarriesTheRowIdAsUserData128() {
            // given
            accepted(transfer("pay-1", "order-1"));
            long rowId = storage.transactions().inTransaction(() -> storage.store().find("pay-1")).orElseThrow().rowId();

            // when
            worker.runOnce();

            // then
            assertThat(fake.requests().get(0).get(0).userData128()).as("보낸 이체의 user_data_128")
                    .isEqualTo(BigInteger.valueOf(rowId));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("TB에 보내지 않고 끝나는 명령이 있으면")
    class WithoutARequest {

        @Test
        @DisplayName("거절로 끝난 예약을 참조한 확정은 TB 요청 없이 건너뜀으로 끝난다")
        void aPostOfARejectedReservationIsSkippedWithoutARequest() {
            // given: hold-1이 잔액 부족으로 거절되었다
            fake.answer(accepted(reserve("hold-1", "order-1")).transferId(), ExceedsCredits);
            worker.runOnce();
            accepted(LedgerCommand.post("capture-1").orderingKey("order-1").reservation("hold-1").build());

            // when
            worker.runOnce();

            // then
            assertState("capture-1", State.SKIPPED);
            assertThat(fake.requests()).as("TB 요청(hold-1 하나뿐이어야 한다)").hasSize(1);
        }

        @Test
        @DisplayName("핸들러가 실패해 결과를 아는 명령은 TB 요청 없이 그 결과로 끝난다 (§5.4)")
        void aKnownResultCompletesWithoutARequest() {
            // given: 첫 바퀴에서 TB는 기록했지만 핸들러가 실패했다
            handler.failNextCalls(1);
            accepted(transfer("pay-1", "order-1"));
            worker.runOnce();
            storage.elapse(BACKOFF_MAX);

            // when
            worker.runOnce();

            // then
            assertState("pay-1", State.APPLIED);
            assertThat(fake.requests()).as("TB 요청(첫 바퀴 하나뿐이어야 한다)").hasSize(1);
        }

        @Test
        @DisplayName("예약보다 앞 순번의 확정은 멈춘 명령으로 한 번 알리고, 다음 바퀴에는 다시 알리지 않는다 (§5.2 HALT)")
        void aHaltedPostIsReportedOnce() {
            // given: 접수 검사를 지나칠 수 없는 순서라 저장소에 직접 넣는다(LedgerRelayContract와 같은 값)
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
            assertThat(events.events()).as("워커 이벤트").containsOnlyOnce("halted:capture-1");
            assertThat(fake.requests()).as("TB 요청").isEmpty();
        }
    }

    @Nested
    @Order(3)
    @DisplayName("TB가 submit-timeout 안에 답하지 않으면")
    class WhenTheLedgerDoesNotAnswer {

        @BeforeEach
        void givenRepliesAreHeld() {
            fake.holdReplies();
        }

        @Test
        @DisplayName("명령을 되돌려 나중에 다시 보내게 하고, 조회는 처리 중으로 남는다")
        void theCommandsAreReleasedForALaterRetry() {
            // given
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("되돌린 명령 수").isEqualTo(1);
            assertState("pay-1", State.IN_PROGRESS);
            assertThat(events.events()).as("워커 이벤트").contains("submitTimedOut:1");
        }

        @Test
        @DisplayName("보낸 이체의 슬롯은 요청이 실제로 끝날 때까지 돌아오지 않는다 — TB 클라이언트 안에 요청이 남아 있다")
        void aTimedOutRequestKeepsItsSlotsUntilItEnds() {
            // given: 슬롯 4, batch 4인 워커에 다른 키의 이체 둘
            InFlightLimit four = new InFlightLimit(4);
            RelayWorker small = new RelayWorker(relay, fake, four,
                    new WorkerSettings(4, 4, Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(100)),
                    events);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));
            small.runOnce();
            assertThat(four.available()).as("timeout 직후 남은 슬롯").isEqualTo(2);

            // when
            fake.releaseHeldReplies();

            // then
            assertThat(four.available()).as("요청이 끝난 뒤 남은 슬롯").isEqualTo(4);
        }

        @Test
        @DisplayName("timeout 뒤에 늦게 온 응답은 아무것도 쓰지 않는다 — 핸들러도 실행되지 않는다")
        void aLateReplyAfterTheTimeoutWritesNothing() {
            // given
            accepted(transfer("pay-1", "order-1"));
            worker.runOnce();

            // when
            fake.releaseHeldReplies();

            // then
            assertThat(handler.completedFor("pay-1")).as("pay-1의 핸들러 실행").isEmpty();
            assertState("pay-1", State.IN_PROGRESS);
            assertThat(events.events()).as("늦은 응답에 대한 쓰기 시도(있다면 0행으로 막혔을 것)").doesNotContain("fenced:pay-1");
        }

        @Test
        @DisplayName("backoff 뒤 다시 보낼 때도 같은 이체 ID로 보내고, 그 결과로 끝난다 (D7)")
        void theRetryUsesTheSameTransferId() {
            // given: 첫 요청이 timeout으로 되돌려졌고, 이제 TB가 답한다
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
    @DisplayName("TB 요청이 예외로 끝나거나 결과 개수가 틀리면")
    class WhenTheRequestFailsOrTheReplyIsMalformed {

        @Test
        @DisplayName("예외로 끝나면 명령을 되돌리고, 슬롯은 모두 돌아온다")
        void aFailedRequestReleasesItsCommands() {
            // given
            fake.failNext(new IllegalStateException("연결이 끊겼다"));
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("되돌린 명령 수").isEqualTo(1);
            assertThat(events.events()).as("워커 이벤트").contains("submitFailed:1");
            assertThat(slots.available()).as("남은 슬롯").isEqualTo(SETTINGS.maxInFlight());
        }

        @Test
        @DisplayName("결과 개수가 요청과 다르면 아무것도 쓰지 않고 모두 되돌린다 — 순서로 짝지으므로 남의 결과로 끝나지 않게")
        void aReplyWithTheWrongCountWritesNothing() {
            // given
            fake.replyWithCount(1);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));

            // when
            worker.runOnce();

            // then
            assertThat(handler.completedFor("pay-1")).as("pay-1의 핸들러 실행").isEmpty();
            assertState("pay-1", State.IN_PROGRESS);
            assertState("pay-2", State.IN_PROGRESS);
            assertThat(events.events()).as("워커 이벤트").contains("malformedReply:2:1");
        }

        @Test
        @DisplayName("결과 개수가 틀린 응답이면 보낸 명령을 모두 되돌린다 — 처리 중으로 방치하지 않는다")
        void aMalformedReplyReleasesEveryCommand() {
            // given
            fake.replyWithCount(1);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("되돌린 명령 수").isEqualTo(2);
        }

        @Test
        @DisplayName("결과가 요청보다 많아도 아무것도 쓰지 않는다")
        void aReplyWithTooManyResultsWritesNothing() {
            // given
            fake.replyWithCount(3);
            accepted(transfer("pay-1", "order-1"));
            accepted(transfer("pay-2", "order-2"));

            // when
            worker.runOnce();

            // then
            assertThat(handler.completedFor("pay-1")).as("pay-1의 핸들러 실행").isEmpty();
            assertThat(events.events()).as("워커 이벤트").contains("malformedReply:2:3");
        }

        @Test
        @DisplayName("요청을 보내는 자리에서 바로 던지면 되돌리고, 슬롯은 모두 돌아온다")
        void aSynchronousSubmitFailureReleasesAndReturnsSlots() {
            // given
            fake.throwOnNextSubmit(new IllegalStateException("배치를 채우다 실패했다"));
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("되돌린 명령 수").isEqualTo(1);
            assertThat(slots.available()).as("남은 슬롯").isEqualTo(1024);
            assertThat(events.events()).as("워커 이벤트").contains("submitFailed:1");
        }

        @Test
        @DisplayName("TB 클라이언트를 더 쓸 수 없다는 실패는 일시적 실패와 따로 알린다 — 운영자가 재시작이 필요함을 알게")
        void anUnavailableLedgerIsReportedDistinctly() {
            // given
            fake.failNext(new LedgerUnavailableException("클라이언트가 닫혔다", new IllegalStateException()));
            accepted(transfer("pay-1", "order-1"));

            // when
            worker.runOnce();

            // then
            assertThat(events.events()).as("워커 이벤트").contains("ledgerUnavailable").doesNotContain("submitFailed:1");
        }
    }

    @Nested
    @Order(7)
    @DisplayName("결과를 쓰다가 0행이거나 핸들러가 실패하면")
    class WhenAResultWriteDoesNotLand {

        @Test
        @DisplayName("핸들러가 실패하면 그 명령을 알리고, 완료로 세지 않는다")
        void aHandlerFailureIsReported() {
            // given
            handler.failNextCalls(1);
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(events.events()).as("워커 이벤트").contains("handlerFailed:pay-1");
            assertThat(report.completed()).as("완료로 센 명령 수").isZero();
        }

        @Test
        @DisplayName("응답을 기다리는 사이 다른 워커가 잡아 갔으면 0행으로 알리고, 완료로 세지 않는다")
        void aFencedWriteIsReported() {
            // given: TB에 보내는 사이 lease가 끝나 다른 워커가 같은 명령을 잡았다
            accepted(transfer("pay-1", "order-1"));
            RelayWorker overtaken = new RelayWorker(relay, batch -> {
                storage.elapse(SETTINGS.lease().plusSeconds(1));
                relay.claim(10, SETTINGS.lease());
                return fake.submit(batch);
            }, slots, SETTINGS, events);

            // when
            CycleReport report = overtaken.runOnce();

            // then
            assertThat(events.events()).as("워커 이벤트").contains("fenced:pay-1");
            assertThat(report.completed()).as("완료로 센 명령 수").isZero();
        }
    }

    @Nested
    @Order(5)
    @DisplayName("in-flight 슬롯이 모자라면")
    class WhenSlotsRunOut {

        @Test
        @DisplayName("claim하지 않고 쉰다 — 명령은 접수됨 그대로이고 lease도 잡히지 않는다 (배압)")
        void noSlotsMeansNoClaim() {
            // given: 다른 쪽이 슬롯을 모두 쥐었다
            slots.tryTake(SETTINGS.maxInFlight(), Duration.ZERO);
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report).as("한 바퀴의 리포트").isEqualTo(CycleReport.IDLE);
            assertState("pay-1", State.ACCEPTED);
            assertThat(storage.transactions().inTransaction(() -> storage.store().find("pay-1")).orElseThrow()
                    .row().leaseToken()).as("pay-1의 lease 토큰").isZero();
            assertThat(events.events()).as("워커 이벤트").contains("noSlots");
        }

        @Test
        @DisplayName("보내지 않은 몫의 슬롯은 바로 돌아온다 — 명령 하나면 batch 128 중 127이 돌아온다")
        void unusedSlotsAreGivenBackAtOnce() {
            // given
            fake.holdReplies();
            accepted(transfer("pay-1", "order-1"));

            // when
            worker.runOnce();

            // then
            assertThat(slots.available()).as("붙잡힌 요청 하나가 쥔 뒤 남은 슬롯").isEqualTo(SETTINGS.maxInFlight() - 1);
        }
    }

    @Nested
    @Order(6)
    @DisplayName("결과를 쓰는 중에 저장소가 한 명령에서 실패하면")
    class WhenAWriteFails {

        @Test
        @DisplayName("그 명령만 알리고 나머지 명령의 결과는 그대로 쓴다 — 받은 거절 사유가 IdAlreadyFailed로 덮이지 않게 (§5.3-3)")
        void theOtherResultsOfTheBatchAreStillWritten() {
            // given: pay-1의 결과를 쓸 때 저장소가 한 번 실패한다(제출 기록 때의 조회는 지나간다)
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
                    .as("pay-2의 거절 코드").isEqualTo(ExceedsCredits);
            assertThat(events.events()).as("워커 이벤트").contains("writeFailed:pay-1");
            assertThat(fake.requests().get(0)).as("TB에 간 요청(실패가 결과 쓰기에서 났다)").hasSize(2);
        }

        @Test
        @DisplayName("제출 기록이 실패한 명령은 TB에 보내지 않는다 — 기록하지 못한 제출이 TB에 남지 않게")
        void aCommandWhoseSubmissionWasNotRecordedIsNotSent() {
            // given: pay-1의 제출을 기록할 때 저장소가 한 번 실패한다
            FlakyStore flaky = new FlakyStore(storage.store(), "pay-1", 0);
            CommandLifecycle onFlaky = new CommandLifecycle(storage.keys(), flaky, storage.transactions(), new SequentialTransferIds(),
                    handler, new Backoff(Duration.ofMillis(200), BACKOFF_MAX));
            RelayWorker flakyWorker = new RelayWorker(onFlaky, fake, slots, SETTINGS, events);
            storage.transactions().inTransaction(() -> onFlaky.submit(transfer("pay-1", "order-1")));
            flaky.arm();

            // when
            flakyWorker.runOnce();

            // then
            assertThat(fake.requests()).as("TB 요청").isEmpty();
            assertThat(events.events()).as("워커 이벤트").contains("writeFailed:pay-1");
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /**
     * {@link #arm()} 뒤에 이 commandId를 skip번 조회한 다음의 조회 한 번에서 던지는 저장소. 나머지는 그대로 전한다. 접수가 하는
     * 조회는 세지 않도록 접수를 마친 뒤 켠다.
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
                throw new IllegalStateException("DB 연결이 잠깐 끊겼다");
            }
            return real.find(id);
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
    @DisplayName("설정과 슬롯이 어긋나면")
    class WhenSlotsDisagreeWithSettings {

        @Test
        @DisplayName("워커를 만들 수 없다 — 설정의 max-in-flight가 실제 상한이 아닌 채로 돌지 않게 (02d I5)")
        void aWorkerRefusesSlotsThatDisagreeWithMaxInFlight() {
            // when
            Throwable refused = org.assertj.core.api.Assertions.catchThrowable(
                    () -> new RelayWorker(relay, fake, new InFlightLimit(512), SETTINGS, events));

            // then
            assertThat(refused).as("거부").isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("512").hasMessageContaining("1024");
        }
    }

    @Nested
    @Order(9)
    @DisplayName("TB 클라이언트가 future 대신 null을 주면")
    class WhenTheClientReturnsNull {

        @Test
        @DisplayName("요청 실패로 보고 명령을 되돌리며, 슬롯은 모두 돌아온다 (02d I7)")
        void aNullReplyGivesTheSlotsBackAndReleasesTheBatch() {
            // given
            fake.replyWithNull();
            accepted(transfer("pay-1", "order-1"));

            // when
            CycleReport report = worker.runOnce();

            // then
            assertThat(report.released()).as("되돌린 명령 수").isEqualTo(1);
            assertThat(slots.available()).as("남은 슬롯").isEqualTo(1024);
            assertThat(events.events()).as("워커 이벤트").contains("submitFailed:1");
        }
    }

    private void insertedPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + row.commandId() + "가 이미 있었다");
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
        assertThat(relay.status(commandId).orElseThrow().state()).as("%s의 조회 상태", commandId).isEqualTo(expected);
    }

    /** TB가 받은 요청마다 그 안의 이체 ID를 순서대로 본다. */
    @SafeVarargs
    private void assertRequests(List<BigInteger>... expected) {
        List<List<BigInteger>> actual = fake.requests().stream()
                .map(request -> request.stream().map(Submission::transferId).toList())
                .toList();
        assertThat(actual).as("TB가 받은 요청(이체 ID)").containsExactly(expected);
    }
}
