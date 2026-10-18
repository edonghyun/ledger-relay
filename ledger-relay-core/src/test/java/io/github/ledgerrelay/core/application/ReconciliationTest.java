package io.github.ledgerrelay.core.application;

import static io.github.ledgerrelay.core.LedgerResultCode.ExceedsCredits;
import static io.github.ledgerrelay.core.LedgerResultCode.ExistsWithDifferentAmount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.ReconciliationReport;
import io.github.ledgerrelay.core.ReconciliationReport.Mismatch;
import io.github.ledgerrelay.core.spi.LedgerTransfer;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.core.testing.FakeLedger;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("대사 — PG의 최종 상태와 TB에 실제로 있는 이체를 맞춘다 (03b)")
class ReconciliationTest {

    static final WorkerSettings SETTINGS =
            new WorkerSettings(128, 1024, Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(100));
    static final Duration LOOKUP_TIMEOUT = Duration.ofMillis(300);
    /** 이체 ID 생성기는 1부터 차례로 낸다. 테스트의 첫 접수가 받는 ID다. */
    static final BigInteger FIRST_TRANSFER_ID = BigInteger.ONE;

    InMemoryStorage storage;
    CommandLifecycle relay;
    FakeLedger fake;
    RelayWorker worker;
    RelayOperator operator;

    @BeforeEach
    void givenARelayOnAFakeLedger() {
        storage = InMemoryStorage.create();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
                new RecordingHandler(), new Backoff(Duration.ofMillis(200), Duration.ofSeconds(1)));
        fake = new FakeLedger();
        worker = new RelayWorker(relay, fake, new InFlightLimit(SETTINGS.maxInFlight()), SETTINGS, WorkerEvents.NONE);
        operator = new RelayOperator(storage.store(), storage.transactions(), relay, fake, LOOKUP_TIMEOUT);
    }

    @Nested
    @Order(1)
    @DisplayName("PG와 TB가 같으면")
    class InAgreement {

        @Test
        @DisplayName("적용된 명령이 모두 일치한다")
        void everythingAppliedMatches() {
            // given
            processed(transfer("pay-1", "order-1"), transfer("pay-2", "order-2"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.checked()).as("확인한 명령").isEqualTo(2);
            assertThat(report.matched()).as("일치").isEqualTo(2);
            assertThat(report.mismatches()).as("어긋남").isEmpty();
        }

        @Test
        @DisplayName("예약이 적용된 뒤 확정돼도 예약은 일치한다 — TB에는 보류 이체가 그대로 있다")
        void aReservationLaterPostedStillMatches() {
            // given
            processed(LedgerCommand.reserve("hold-1").orderingKey("order-1").debit(1).credit(2).amount(500).ledger(1)
                    .code(1).timeoutSeconds(60).build());
            processed(LedgerCommand.post("capture-1").orderingKey("order-1").reservation("hold-1").build());

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.checked()).as("확인한 명령").isEqualTo(2);
            assertThat(report.matched()).as("일치").isEqualTo(2);
        }

        @Test
        @DisplayName("거절된 명령은 TB에 없으므로 일치한다")
        void aRejectionAbsentFromTheLedgerMatches() {
            // given
            fake.answer(FIRST_TRANSFER_ID, ExceedsCredits);
            processed(transfer("pay-1", "order-1"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.matched()).as("일치").isEqualTo(1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("PG와 TB가 다르면")
    class InDisagreement {

        @Test
        @DisplayName("PG는 적용인데 TB에 없으면 TB에 없음으로 알린다")
        void anAppliedCommandMissingInTheLedgerIsReported() {
            // given
            processed(transfer("pay-1", "order-1"));
            fake.forget(transferIdOf("pay-1"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.mismatches()).as("어긋남")
                    .containsExactly(new Mismatch("pay-1", transferIdOf("pay-1"), Mismatch.Kind.MISSING_IN_LEDGER));
        }

        @Test
        @DisplayName("PG는 거절인데 TB에 있으면 TB에 있으면 안 됨으로 알린다")
        void aRejectedCommandFoundInTheLedgerIsReported() {
            // given
            fake.answer(FIRST_TRANSFER_ID, ExceedsCredits);
            processed(transfer("pay-1", "order-1"));
            fake.plant(new LedgerTransfer(transferIdOf("pay-1"), BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(500),
                    1, 1, CommandKind.TRANSFER));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.mismatches()).as("어긋남")
                    .containsExactly(new Mismatch("pay-1", transferIdOf("pay-1"), Mismatch.Kind.UNEXPECTED_IN_LEDGER));
        }

        @Test
        @DisplayName("금액이 다르면 다름으로 알린다")
        void aDifferentAmountIsReported() {
            // given
            processed(transfer("pay-1", "order-1"));
            fake.alterAmount(transferIdOf("pay-1"), BigInteger.valueOf(501));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.mismatches()).as("어긋남")
                    .containsExactly(new Mismatch("pay-1", transferIdOf("pay-1"), Mismatch.Kind.DIFFERENT));
        }
    }

    @Nested
    @Order(3)
    @DisplayName("범위와 실패")
    class PagingAndFailure {

        @Test
        @DisplayName("충돌로 끝난 명령은 보지 않는다 — 그 ID의 TB 이체는 우리 것이 아니다")
        void conflictsAreNotChecked() {
            // given
            fake.answer(FIRST_TRANSFER_ID, ExistsWithDifferentAmount);
            processed(transfer("pay-1", "order-1"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.checked()).as("확인한 명령").isZero();
            assertThat(report.lastPosition()).as("마지막 위치").isEqualTo(rowIdOf("pay-1"));
        }

        @Test
        @DisplayName("마지막 위치부터 이어서 다음 페이지를 본다")
        void theLastPositionContinuesTheNextPage() {
            // given
            processed(transfer("pay-1", "order-1"), transfer("pay-2", "order-2"), transfer("pay-3", "order-3"));
            ReconciliationReport first = operator.reconcile(0, 2);

            // when
            ReconciliationReport second = operator.reconcile(first.lastPosition(), 2);

            // then
            assertThat(first.checked()).as("첫 페이지").isEqualTo(2);
            assertThat(first.lastPosition()).as("첫 페이지의 마지막 위치").isEqualTo(rowIdOf("pay-2"));
            assertThat(second.checked()).as("둘째 페이지").isEqualTo(1);
        }

        @Test
        @DisplayName("TB가 조회에 답하지 않으면 TB를 쓸 수 없음으로 끝난다 — 끝없이 기다리지 않는다")
        void aSilentLedgerFailsAsUnavailable() {
            // given
            processed(transfer("pay-1", "order-1"));
            fake.holdLookups();

            // then
            assertThatThrownBy(() -> operator.reconcile(0, 10)).as("대사").isInstanceOf(LedgerUnavailableException.class);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private void processed(LedgerCommand... commands) {
        for (LedgerCommand command : commands) {
            storage.transactions().inTransaction(() -> relay.submit(command));
        }
        worker.runOnce();
    }

    private BigInteger transferIdOf(String commandId) {
        return relay.status(commandId).orElseThrow().transferId();
    }

    private long rowIdOf(String commandId) {
        return storage.transactions().inTransaction(() -> storage.store().find(commandId)).orElseThrow().rowId();
    }

    private static LedgerCommand transfer(String commandId, String orderingKey) {
        return LedgerCommand.transfer(commandId).orderingKey(orderingKey)
                .debit(1).credit(2).amount(500).ledger(1).code(1).build();
    }
}
