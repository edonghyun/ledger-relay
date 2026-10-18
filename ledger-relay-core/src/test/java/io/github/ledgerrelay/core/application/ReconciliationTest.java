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

@DisplayName("Reconciliation — matches final states in PG against the transfers that actually exist in TB")
class ReconciliationTest {

    static final WorkerSettings SETTINGS =
            new WorkerSettings(128, 1024, Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(100));
    static final Duration LOOKUP_TIMEOUT = Duration.ofMillis(300);
    /**
     * The transfer id generator hands out ids from 1 in order. This is the id the test's first accepted command gets.
     */
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
    @DisplayName("When PG and TB agree")
    class InAgreement {

        @Test
        @DisplayName("Every applied command matches")
        void everythingAppliedMatches() {
            // given
            processed(transfer("pay-1", "order-1"), transfer("pay-2", "order-2"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.checked()).as("commands checked").isEqualTo(2);
            assertThat(report.matched()).as("matched").isEqualTo(2);
            assertThat(report.mismatches()).as("mismatches").isEmpty();
        }

        @Test
        @DisplayName("A reservation still matches after it is posted — the pending transfer stays in TB as is")
        void aReservationLaterPostedStillMatches() {
            // given
            processed(LedgerCommand.reserve("hold-1").orderingKey("order-1").debit(1).credit(2).amount(500).ledger(1)
                    .code(1).timeoutSeconds(60).build());
            processed(LedgerCommand.post("capture-1").orderingKey("order-1").reservation("hold-1").build());

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.checked()).as("commands checked").isEqualTo(2);
            assertThat(report.matched()).as("matched").isEqualTo(2);
        }

        @Test
        @DisplayName("A rejected command is not in TB, so it matches")
        void aRejectionAbsentFromTheLedgerMatches() {
            // given
            fake.answer(FIRST_TRANSFER_ID, ExceedsCredits);
            processed(transfer("pay-1", "order-1"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.matched()).as("matched").isEqualTo(1);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When PG and TB disagree")
    class InDisagreement {

        @Test
        @DisplayName("Applied in PG but absent from TB is reported as missing in TB")
        void anAppliedCommandMissingInTheLedgerIsReported() {
            // given
            processed(transfer("pay-1", "order-1"));
            fake.forget(transferIdOf("pay-1"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.mismatches()).as("mismatches")
                    .containsExactly(new Mismatch("pay-1", transferIdOf("pay-1"), Mismatch.Kind.MISSING_IN_LEDGER));
        }

        @Test
        @DisplayName("Rejected in PG but present in TB is reported as unexpected in TB")
        void aRejectedCommandFoundInTheLedgerIsReported() {
            // given
            fake.answer(FIRST_TRANSFER_ID, ExceedsCredits);
            processed(transfer("pay-1", "order-1"));
            fake.plant(new LedgerTransfer(transferIdOf("pay-1"), BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(500),
                    1, 1, CommandKind.TRANSFER));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.mismatches()).as("mismatches")
                    .containsExactly(new Mismatch("pay-1", transferIdOf("pay-1"), Mismatch.Kind.UNEXPECTED_IN_LEDGER));
        }

        @Test
        @DisplayName("A different amount is reported as different")
        void aDifferentAmountIsReported() {
            // given
            processed(transfer("pay-1", "order-1"));
            fake.alterAmount(transferIdOf("pay-1"), BigInteger.valueOf(501));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.mismatches()).as("mismatches")
                    .containsExactly(new Mismatch("pay-1", transferIdOf("pay-1"), Mismatch.Kind.DIFFERENT));
        }
    }

    @Nested
    @Order(3)
    @DisplayName("Paging and failure")
    class PagingAndFailure {

        @Test
        @DisplayName("Commands that ended in conflict are not checked — the TB transfer with that id is not ours")
        void conflictsAreNotChecked() {
            // given
            fake.answer(FIRST_TRANSFER_ID, ExistsWithDifferentAmount);
            processed(transfer("pay-1", "order-1"));

            // when
            ReconciliationReport report = operator.reconcile(0, 10);

            // then
            assertThat(report.checked()).as("commands checked").isZero();
            assertThat(report.lastPosition()).as("last position").isEqualTo(rowIdOf("pay-1"));
        }

        @Test
        @DisplayName("The next page continues from the last position")
        void theLastPositionContinuesTheNextPage() {
            // given
            processed(transfer("pay-1", "order-1"), transfer("pay-2", "order-2"), transfer("pay-3", "order-3"));
            ReconciliationReport first = operator.reconcile(0, 2);

            // when
            ReconciliationReport second = operator.reconcile(first.lastPosition(), 2);

            // then
            assertThat(first.checked()).as("first page").isEqualTo(2);
            assertThat(first.lastPosition()).as("last position of the first page").isEqualTo(rowIdOf("pay-2"));
            assertThat(second.checked()).as("second page").isEqualTo(1);
        }

        @Test
        @DisplayName("When TB does not answer the lookup, it ends with TB unavailable — it does not wait forever")
        void aSilentLedgerFailsAsUnavailable() {
            // given
            processed(transfer("pay-1", "order-1"));
            fake.holdLookups();

            // then
            assertThatThrownBy(() -> operator.reconcile(0, 10)).as("reconciliation").isInstanceOf(LedgerUnavailableException.class);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

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
