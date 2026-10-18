package io.github.ledgerrelay.jdbc;

import static io.github.ledgerrelay.core.LedgerResultCode.Created;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.ClaimedCommand;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.OtherTransaction;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import io.github.ledgerrelay.core.testing.TestTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The command store ({@code CommandStore}) does not lock ordering key rows. Worker writes, during which the handler
 * runs, use only this store, so if the store locked key rows internally it would deadlock with an app transaction
 * that does "order row → submit". So every store method must finish without waiting even while another transaction
 * holds the key row.
 */
@DisplayName("The command store does not lock ordering key rows — it does not wait even when a transaction holds the key row")
class KeyLockFreeStoreTest {

    static final Duration NOT_BLOCKED = Duration.ofSeconds(2);
    static final Duration LEASE = Duration.ofSeconds(30);

    PostgresStorage storage;
    ClaimedCommand pay1;
    OtherTransaction<Long> keyHolder;

    @BeforeEach
    void givenAnotherTransactionHoldsTheKeyRowOfASubmittedHead() {
        storage = PostgresStorage.withDataSourceTransactions();
        CommandLifecycle relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(),
                new SequentialTransferIds(), new RecordingHandler(),
                new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-1").orderingKey("order-1")
                .debit(1).credit(2).amount(500).ledger(1).code(1).build()));
        pay1 = relay.claim(10, LEASE).claimed().get(0);
        relay.markSubmitted(pay1);
        keyHolder = OtherTransaction.start(storage.transactions(), () -> storage.sql()
                .sql("SELECT next_seq FROM ledger_ordering_key WHERE ordering_key = 'order-1' FOR UPDATE")
                .query(Long.class).single());
        keyHolder.awaitWork();
    }

    @AfterEach
    void releaseTheKeyRow() {
        keyHolder.close();
    }

    @Test
    @DisplayName("Writing the result of the head does not wait — so the handler never runs while holding the key row")
    void writingTheHeadsResultDoesNotWait() {
        // when
        boolean written = finishedWithin(() -> storage.store().compareAndSet(appliedPay1()));

        // then
        assertThat(written).as("writing the result of pay-1").isTrue();
    }

    @Test
    @DisplayName("Finding and locking candidates do not wait either")
    void findingAndLockingCandidatesDoNotWait() {
        // when
        Optional<CommandRecord> found = finishedWithin(() -> storage.store().find("pay-1"));
        List<DueCommand> due = finishedWithin(() -> storage.store().lockDue(10));

        // then
        assertThat(found).as("found pay-1").isPresent();
        assertThat(due).as("candidates when the only head still has its lease").isEmpty();
    }

    @Test
    @DisplayName("Inserting a row on the same key does not wait — only a submit that already took its sequence number inserts, so taking the sequence number locks the key row")
    void insertingOnTheKeyDoesNotWait() {
        // when
        Optional<CommandRecord> existing = finishedWithin(() -> storage.store().insertIfAbsent(pay2()));

        // then
        assertThat(existing).as("existing row").isEmpty();
    }

    // ── Vocabulary used only in this file ──────────────────────────────

    private <T> T finishedWithin(java.util.function.Supplier<T> work) {
        try (OtherTransaction<T> other = OtherTransaction.start(storage.transactions(), work)) {
            T result = other.assertFinishedWithin(NOT_BLOCKED);
            other.rollback();
            return result;
        }
    }

    private RowChange appliedPay1() {
        CommandRow applied = new CommandRow(StoredState.APPLIED, pay1.leaseToken(), 1, Optional.of(Created),
                Optional.of(Created), 0);
        return new RowChange(pay1.rowId(), StoredState.CLAIMED, pay1.leaseToken(), applied,
                new RowChange.Stamp.Completion());
    }

    private static NewCommand pay2() {
        return new NewCommand("pay-2", "order-1", 2, Optional.empty(), TestTransferIds.transferIdFor("order-1", 2),
                "hash-of-pay-2", new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                        BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }
}
