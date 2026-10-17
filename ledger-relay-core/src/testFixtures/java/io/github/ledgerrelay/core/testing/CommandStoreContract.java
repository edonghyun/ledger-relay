package io.github.ledgerrelay.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Supplier;
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
            assertThat(inTx(() -> store.find("never-inserted"))).isEmpty();
        }
    }

    @Nested
    @Order(3)
    @DisplayName("트랜잭션이 — G1, G6")
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
            assertThatThrownBy(() -> storage.transactions().requireActive())
                    .isInstanceOf(storage.outsideTransactionError());
        }

        @Test
        @DisplayName("안에서 활성 트랜잭션을 요구하면 통과한다")
        void requiringATransactionInsideOnePasses() {
            inTx(() -> storage.transactions().requireActive());
        }
    }

    // ── 행을 만드는 말: 값은 숨기지 않는다. 이체 ID는 101부터, 해시는 "hash-of-<id>"다 ─────────

    protected static NewCommand newTransfer(String commandId, String orderingKey, long seq) {
        return new NewCommand(commandId, orderingKey, seq, Optional.empty(), BigInteger.valueOf(100 + seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    protected static NewCommand newReserve(String commandId, String orderingKey, long seq) {
        return new NewCommand(commandId, orderingKey, seq, Optional.empty(), BigInteger.valueOf(100 + seq),
                "hash-of-" + commandId, new TransferBody(CommandKind.RESERVE, BigInteger.valueOf(1),
                        BigInteger.valueOf(2), BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }

    /** 예약의 이체 ID는 예약이 순번 1로 들어갔다고 보고 101로 둔다. */
    protected static NewCommand newPost(String commandId, String orderingKey, long seq, String reservationCommandId) {
        return new NewCommand(commandId, orderingKey, seq, Optional.of(reservationCommandId),
                BigInteger.valueOf(100 + seq), "hash-of-" + commandId,
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

    /** 테스트가 일부러 던지는 롤백. */
    protected static final class Rollback extends RuntimeException {
        Rollback() {
            super("테스트가 일부러 롤백한다");
        }
    }
}
