package io.github.ledgerrelay.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("다른 트랜잭션 어휘 — 동시성 장이 믿는 도구 (docs/testing.md §6)")
class OtherTransactionTest {

    static final Duration BLOCKED = Duration.ofMillis(300);

    InMemoryStorage storage;

    @BeforeEach
    void givenAnEmptyStore() {
        storage = InMemoryStorage.create();
    }

    @Nested
    @Order(1)
    @DisplayName("한 트랜잭션이 열려 있는 동안 다른 트랜잭션을 시작하면")
    class WhileOneIsOpen {

        @Test
        @DisplayName("두 번째는 첫 번째가 커밋할 때까지 기다린 뒤 끝난다")
        void theSecondWaitsForTheFirst() {
            // given
            try (OtherTransaction<String> first = OtherTransaction.start(storage.transactions(), () -> "first")) {
                first.awaitWork();

                // when: 두 번째는 첫 번째가 커밋할 때까지 막혀 있다
                try (OtherTransaction<String> second = OtherTransaction.start(storage.transactions(), () -> "second")) {
                    second.assertStillWorkingAfter(BLOCKED);
                    first.commit();

                    // then
                    assertThat(second.awaitWork()).as("두 번째 트랜잭션의 결과").isEqualTo("second");
                }
            }
        }

        @Test
        @DisplayName("막혀 있지 않은데 막혀 있다고 확인하면 실패한다 — 어휘가 거짓말하지 않게")
        void assertingABlockThatIsNotThereFails() {
            // given
            try (OtherTransaction<String> done = OtherTransaction.start(storage.transactions(), () -> "done")) {
                done.awaitWork();

                // when
                ThrowingCallable assertingABlock = () -> done.assertStillWorkingAfter(BLOCKED);

                // then
                assertThatThrownBy(assertingABlock).as("끝난 일을 막혀 있다고 본 확인").isInstanceOf(AssertionError.class);
            }
        }

        @Test
        @DisplayName("막혀 있는데 막히지 않았다고 확인하면 실패한다 — 어휘가 거짓말하지 않게")
        void assertingNoBlockWhileBlockedFails() {
            // given
            try (OtherTransaction<String> first = OtherTransaction.start(storage.transactions(), () -> "first")) {
                first.awaitWork();
                try (OtherTransaction<String> second = OtherTransaction.start(storage.transactions(), () -> "second")) {

                    // when
                    ThrowingCallable assertingNoBlock = () -> second.assertFinishedWithin(BLOCKED);

                    // then
                    assertThatThrownBy(assertingNoBlock).as("막힌 일을 끝났다고 본 확인").isInstanceOf(AssertionError.class);
                }
            }
        }

        @Test
        @DisplayName("롤백하면 그 안에서 넣은 행이 남지 않는다")
        void rollingBackLeavesNothing() {
            // given
            OtherTransaction<Optional<CommandRecord>> inserting = OtherTransaction.start(storage.transactions(),
                    () -> storage.store().insertIfAbsent(pay1()));
            inserting.awaitWork();

            // when
            inserting.rollback();

            // then
            assertThat(storage.transactions().inTransaction(() -> storage.store().find("pay-1")))
                    .as("롤백한 트랜잭션이 넣은 행").isEmpty();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("끝내라는 지시가 상한 안에 오지 않으면")
    class WhenNeverToldHowToEnd {

        @Test
        @DisplayName("스스로 롤백한다 — 실패한 테스트가 다음 테스트에 행을 남기지 않게")
        void itRollsBackOnItsOwn() throws InterruptedException {
            // given: 상한 200ms로 연 트랜잭션이 pay-1을 넣었다
            OtherTransaction<Optional<CommandRecord>> forgotten = OtherTransaction.start(storage.transactions(),
                    () -> storage.store().insertIfAbsent(pay1()), Duration.ofMillis(200));
            forgotten.awaitWork();

            // when: commit()도 rollback()도 부르지 않고 상한이 지나도록 둔다
            Thread.sleep(1_000);

            // then
            assertThat(storage.transactions().inTransaction(() -> storage.store().find("pay-1")))
                    .as("지시 없이 끝난 트랜잭션이 넣은 행").isEmpty();
        }
    }

    /** {@code CommandStoreContract.newTransfer("pay-1", "order-1", 1)}과 같은 값 */
    private static NewCommand pay1() {
        return new NewCommand("pay-1", "order-1", 1, Optional.empty(), BigInteger.valueOf(101), "hash-of-pay-1",
                new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                        BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }
}
