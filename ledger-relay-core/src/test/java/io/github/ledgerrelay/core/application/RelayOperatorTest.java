package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandBacklog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.AbandonOutcome;
import io.github.ledgerrelay.core.CannotAbandonException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandNotFoundException;
import io.github.ledgerrelay.core.HaltReason;
import io.github.ledgerrelay.core.HaltedCommand;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.SkipReason;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.testing.FakeLedger;
import io.github.ledgerrelay.core.testing.InMemoryStorage;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.SequentialTransferIds;
import io.github.ledgerrelay.core.testing.TestTransferIds;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Operator port — listing and abandoning halted commands (O5)")
class RelayOperatorTest {

    static final Duration LEASE = Duration.ofSeconds(30);

    InMemoryStorage storage;
    RecordingHandler handler;
    CommandLifecycle relay;
    RelayOperator operator;

    @BeforeEach
    void givenCapture1IsHaltedAheadOfItsReservation() {
        storage = InMemoryStorage.create();
        handler = new RecordingHandler();
        relay = new CommandLifecycle(storage.keys(), storage.store(), storage.transactions(), new SequentialTransferIds(),
                handler, new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20)));
        operator = new RelayOperator(storage.store(), storage.transactions(), relay, new FakeLedger(), Duration.ofSeconds(1));
        insertedPastTheChecks(reserveRow("hold-1", 2));
        insertedPastTheChecks(postRow("capture-1", 1, "hold-1"));
        relay.claim(10, LEASE);
    }

    @Nested
    @Order(1)
    @DisplayName("When the halted commands are listed")
    class Listing {

        @Test
        @DisplayName("Each command shows its key, sequence number, kind, reason and time")
        void listsHaltedCommandsWithReason() {
            // when
            List<HaltedCommand> halted = operator.haltedCommands(0, 10);

            // then
            assertThat(halted).as("halted commands").singleElement().satisfies(command -> {
                assertThat(command.commandId()).as("commandId").isEqualTo("capture-1");
                assertThat(command.orderingKey()).as("ordering key").isEqualTo("order-1");
                assertThat(command.seq()).as("sequence number").isEqualTo(1);
                assertThat(command.kind()).as("kind").isEqualTo(CommandKind.POST);
                assertThat(command.reason()).as("reason").isEqualTo(HaltReason.RESERVATION_NOT_EARLIER);
                assertThat(command.haltedAt()).as("halted at").isEqualTo(storage.clock().get());
            });
        }

        @Test
        @DisplayName("Asking for fewer than 1 or more than 1000 at a time is refused")
        void aLimitOutsideTheRangeIsRefused() {
            assertThatThrownBy(() -> operator.haltedCommands(0, 0)).as("limit 0").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> operator.haltedCommands(0, 1001)).as("limit 1001")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a command is abandoned")
    class Abandoning {

        @Test
        @DisplayName("The handler receives 'abandoned by operator', the answer is abandoned, and it leaves the list")
        void abandoningRunsTheHandler() {
            // when
            AbandonOutcome outcome = operator.abandonHalted("capture-1");

            // then
            assertThat(outcome).as("abandon outcome").isEqualTo(AbandonOutcome.ABANDONED);
            assertThat(handler.completedFor("capture-1")).as("result the handler received").singleElement()
                    .satisfies(status -> assertThat(status.skipReason()).as("skip reason")
                            .contains(SkipReason.ABANDONED_BY_OPERATOR));
            assertThat(operator.haltedCommands(0, 10)).as("halted commands").isEmpty();
        }

        @Test
        @DisplayName("When the handler fails, the answer is handler failed, and the command stays halted in the list")
        void aFailingHandlerLeavesItHalted() {
            // given
            handler.failNextCalls(1);

            // when
            AbandonOutcome outcome = operator.abandonHalted("capture-1");

            // then
            assertThat(outcome).as("abandon outcome").isEqualTo(AbandonOutcome.HANDLER_FAILED);
            assertThat(operator.haltedCommands(0, 10)).as("halted commands").extracting(HaltedCommand::commandId)
                    .containsExactly("capture-1");
        }

        @Test
        @DisplayName("A command that was never accepted is refused as not found")
        void abandoningAnUnknownCommandIsRefused() {
            assertThatThrownBy(() -> operator.abandonHalted("never-accepted")).as("refusal")
                    .isInstanceOf(CommandNotFoundException.class);
        }

        @Test
        @DisplayName("A command that is not halted is refused as 'not halted'")
        void abandoningACommandThatIsNotHaltedIsRefused() {
            // given
            storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-9").orderingKey("order-9")
                    .debit(1).credit(2).amount(500).ledger(1).code(1).build()));

            // then
            assertThatThrownBy(() -> operator.abandonHalted("pay-9")).as("refusal")
                    .isInstanceOfSatisfying(CannotAbandonException.class, refused -> assertThat(refused.reason())
                            .as("refusal reason").isEqualTo(CannotAbandonException.Reason.NOT_HALTED));
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    @Nested
    @Order(3)
    @DisplayName("When the backlog is read")
    class ReadingTheBacklog {

        @Test
        @DisplayName("It counts each waiting state and measures the longest wait on the store's clock")
        void backlogReportsAgeFromTheStoreClock() {
            // given: hold-1 waits behind the halted capture-1 for 90 seconds
            storage.elapse(Duration.ofSeconds(90));

            // when
            CommandBacklog backlog = operator.backlog();

            // then
            assertThat(backlog.pending()).as("pending commands").isEqualTo(1);
            assertThat(backlog.claimed()).as("in-progress commands").isZero();
            assertThat(backlog.halted()).as("halted commands").isEqualTo(1);
            assertThat(backlog.oldestWaitingAge()).as("longest wait").contains(Duration.ofSeconds(90));
            assertThat(backlog.readAt()).as("read at").isEqualTo(storage.clock().get());
        }
    }

    private void insertedPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given setup failed: " + row.commandId() + " already existed");
        }
    }

    private static NewCommand reserveRow(String commandId, long seq) {
        return new NewCommand(commandId, "order-1", seq, Optional.empty(), TestTransferIds.transferIdFor("order-1", seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    private static NewCommand postRow(String commandId, long seq, String reservationCommandId) {
        return new NewCommand(commandId, "order-1", seq, Optional.of(reservationCommandId),
                TestTransferIds.transferIdFor("order-1", seq), "hash-of-" + commandId,
                new TransferBody(CommandKind.POST, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(500), 0, 0, 0,
                        Optional.of(TestTransferIds.transferIdFor("order-1", 2)), 0, 0));
    }
}
