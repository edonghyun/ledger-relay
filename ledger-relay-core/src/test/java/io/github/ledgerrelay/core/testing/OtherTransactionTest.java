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

@DisplayName("Other-transaction vocabulary — a tool the concurrency chapters rely on (docs/testing.md)")
class OtherTransactionTest {

    static final Duration BLOCKED = Duration.ofMillis(300);

    InMemoryStorage storage;

    @BeforeEach
    void givenAnEmptyStore() {
        storage = InMemoryStorage.create();
    }

    @Nested
    @Order(1)
    @DisplayName("When another transaction starts while one is open")
    class WhileOneIsOpen {

        @Test
        @DisplayName("The second waits until the first commits, then finishes")
        void theSecondWaitsForTheFirst() {
            // given
            try (OtherTransaction<String> first = OtherTransaction.start(storage.transactions(), () -> "first")) {
                first.awaitWork();

                // when: the second is blocked until the first commits
                try (OtherTransaction<String> second = OtherTransaction.start(storage.transactions(), () -> "second")) {
                    second.assertStillWorkingAfter(BLOCKED);
                    first.commit();

                    // then
                    assertThat(second.awaitWork()).as("result of the second transaction").isEqualTo("second");
                }
            }
        }

        @Test
        @DisplayName("Asserting a block that is not there fails — so the vocabulary does not lie")
        void assertingABlockThatIsNotThereFails() {
            // given
            try (OtherTransaction<String> done = OtherTransaction.start(storage.transactions(), () -> "done")) {
                done.awaitWork();

                // when
                ThrowingCallable assertingABlock = () -> done.assertStillWorkingAfter(BLOCKED);

                // then
                assertThatThrownBy(assertingABlock).as("check that sees finished work as blocked").isInstanceOf(AssertionError.class);
            }
        }

        @Test
        @DisplayName("Asserting no block while blocked fails — so the vocabulary does not lie")
        void assertingNoBlockWhileBlockedFails() {
            // given
            try (OtherTransaction<String> first = OtherTransaction.start(storage.transactions(), () -> "first")) {
                first.awaitWork();
                try (OtherTransaction<String> second = OtherTransaction.start(storage.transactions(), () -> "second")) {

                    // when
                    ThrowingCallable assertingNoBlock = () -> second.assertFinishedWithin(BLOCKED);

                    // then
                    assertThatThrownBy(assertingNoBlock).as("check that sees blocked work as finished").isInstanceOf(AssertionError.class);
                }
            }
        }

        @Test
        @DisplayName("A rollback leaves none of the rows inserted inside it")
        void rollingBackLeavesNothing() {
            // given
            OtherTransaction<Optional<CommandRecord>> inserting = OtherTransaction.start(storage.transactions(),
                    () -> storage.store().insertIfAbsent(pay1()));
            inserting.awaitWork();

            // when
            inserting.rollback();

            // then
            assertThat(storage.transactions().inTransaction(() -> storage.store().find("pay-1")))
                    .as("row inserted by the rolled-back transaction").isEmpty();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When no instruction to end arrives within the limit")
    class WhenNeverToldHowToEnd {

        @Test
        @DisplayName("It rolls back on its own — so a failed test leaves no rows for the next test")
        void itRollsBackOnItsOwn() throws InterruptedException {
            // given: a transaction opened with a 200 ms limit inserted pay-1
            OtherTransaction<Optional<CommandRecord>> forgotten = OtherTransaction.start(storage.transactions(),
                    () -> storage.store().insertIfAbsent(pay1()), Duration.ofMillis(200));
            forgotten.awaitWork();

            // when: neither commit() nor rollback() is called, and the limit is allowed to pass
            Thread.sleep(1_000);

            // then
            assertThat(storage.transactions().inTransaction(() -> storage.store().find("pay-1")))
                    .as("row inserted by the transaction that ended without instruction").isEmpty();
        }
    }

    /** The same values as {@code CommandStoreContract.newTransfer("pay-1", "order-1", 1)} */
    private static NewCommand pay1() {
        return new NewCommand("pay-1", "order-1", 1, Optional.empty(), BigInteger.valueOf(101), "hash-of-pay-1",
                new TransferBody(CommandKind.TRANSFER, BigInteger.valueOf(1), BigInteger.valueOf(2),
                        BigInteger.valueOf(500), 1, 1, 0, Optional.empty(), 0, 0));
    }
}
