package io.github.ledgerrelay.core.application;

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

@DisplayName("운영자 포트 — 멈춘 명령 목록과 포기 (O5)")
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
    @DisplayName("멈춘 명령을 보면")
    class Listing {

        @Test
        @DisplayName("명령마다 키·순번·종류·이유·시각이 보인다")
        void listsHaltedCommandsWithReason() {
            // when
            List<HaltedCommand> halted = operator.haltedCommands(0, 10);

            // then
            assertThat(halted).as("멈춘 명령").singleElement().satisfies(command -> {
                assertThat(command.commandId()).as("commandId").isEqualTo("capture-1");
                assertThat(command.orderingKey()).as("순서 키").isEqualTo("order-1");
                assertThat(command.seq()).as("순번").isEqualTo(1);
                assertThat(command.kind()).as("종류").isEqualTo(CommandKind.POST);
                assertThat(command.reason()).as("이유").isEqualTo(HaltReason.RESERVATION_NOT_EARLIER);
                assertThat(command.haltedAt()).as("멈춘 시각").isEqualTo(storage.clock().get());
            });
        }

        @Test
        @DisplayName("한 번에 1개 미만이나 1000개 넘게 달라고 하면 거부된다")
        void aLimitOutsideTheRangeIsRefused() {
            assertThatThrownBy(() -> operator.haltedCommands(0, 0)).as("limit 0").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> operator.haltedCommands(0, 1001)).as("limit 1001")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("포기하면")
    class Abandoning {

        @Test
        @DisplayName("핸들러가 '운영자 포기'를 받고 포기됨으로 답하며, 목록에서 빠진다")
        void abandoningRunsTheHandler() {
            // when
            AbandonOutcome outcome = operator.abandonHalted("capture-1");

            // then
            assertThat(outcome).as("포기 결과").isEqualTo(AbandonOutcome.ABANDONED);
            assertThat(handler.completedFor("capture-1")).as("핸들러가 받은 결과").singleElement()
                    .satisfies(status -> assertThat(status.skipReason()).as("건너뛴 이유")
                            .contains(SkipReason.ABANDONED_BY_OPERATOR));
            assertThat(operator.haltedCommands(0, 10)).as("멈춘 명령").isEmpty();
        }

        @Test
        @DisplayName("핸들러가 실패하면 핸들러 실패로 답하고, 멈춘 그대로 목록에 남는다")
        void aFailingHandlerLeavesItHalted() {
            // given
            handler.failNextCalls(1);

            // when
            AbandonOutcome outcome = operator.abandonHalted("capture-1");

            // then
            assertThat(outcome).as("포기 결과").isEqualTo(AbandonOutcome.HANDLER_FAILED);
            assertThat(operator.haltedCommands(0, 10)).as("멈춘 명령").extracting(HaltedCommand::commandId)
                    .containsExactly("capture-1");
        }

        @Test
        @DisplayName("접수한 적 없는 명령이면 명령 없음으로 거부된다")
        void abandoningAnUnknownCommandIsRefused() {
            assertThatThrownBy(() -> operator.abandonHalted("never-accepted")).as("거부")
                    .isInstanceOf(CommandNotFoundException.class);
        }

        @Test
        @DisplayName("멈추지 않은 명령이면 '멈추지 않음'으로 거부된다")
        void abandoningACommandThatIsNotHaltedIsRefused() {
            // given
            storage.transactions().inTransaction(() -> relay.submit(LedgerCommand.transfer("pay-9").orderingKey("order-9")
                    .debit(1).credit(2).amount(500).ledger(1).code(1).build()));

            // then
            assertThatThrownBy(() -> operator.abandonHalted("pay-9")).as("거부")
                    .isInstanceOfSatisfying(CannotAbandonException.class, refused -> assertThat(refused.reason())
                            .as("거부 이유").isEqualTo(CannotAbandonException.Reason.NOT_HALTED));
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private void insertedPastTheChecks(NewCommand row) {
        if (storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(row)).isPresent()) {
            throw new IllegalStateException("given 준비 실패: " + row.commandId() + "가 이미 있었다");
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
