package io.github.ledgerrelay.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.ReservationNotFoundException;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import java.math.BigInteger;
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

    protected final RecordingHandler handler = new RecordingHandler();
    protected StorageUnderTest storage;
    protected CommandLifecycle relay;

    /** 테스트마다 비어 있는 저장소 한 벌을 만든다. */
    protected abstract StorageUnderTest createStorage();

    @BeforeEach
    protected void givenAnEmptyRelay() {
        storage = createStorage();
        relay = new CommandLifecycle(storage.store(), storage.transactions(), new SequentialTransferIds(), handler);
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

    /** 앱 트랜잭션이 일부러 실패한다. */
    protected static final class AppFailure extends RuntimeException {
        AppFailure() {
            super("앱이 일부러 롤백한다");
        }
    }
}
