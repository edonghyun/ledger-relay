package io.github.ledgerrelay.tigerbeetle.testing;

import static com.tigerbeetle.CreateTransferStatus.Created;
import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static com.tigerbeetle.CreateTransferStatus.ExceedsDebits;
import static com.tigerbeetle.CreateTransferStatus.Exists;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentAmount;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentCode;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentCreditAccountId;
import static com.tigerbeetle.CreateTransferStatus.IdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertAlreadyRecorded;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertConflictOnAmount;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertContentConflict;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedBecauseIdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedFor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Result-checking vocabulary — docs/testing.md §5")
class LedgerAssertionsTest {

    @Nested
    @Order(1)
    @DisplayName("An assert that checks a result code")
    class ResultAssertions {

        @Test
        @DisplayName("Passes quietly on the expected result")
        void passesOnTheExpectedResult() {
            assertCreated(Created);
            assertAlreadyRecorded(Exists);
            assertConflictOnAmount(ExistsWithDifferentAmount);
            assertContentConflict(ExistsWithDifferentCode, ExistsWithDifferentCode);
            assertRejectedBecauseIdAlreadyFailed(IdAlreadyFailed);
            assertRejectedFor(ExceedsCredits, ExceedsCredits);
        }

        @Test
        @DisplayName("Fails on another result, naming the expected meaning, the TB code and the actual code")
        void failsWithMeaningAndCodes() {
            assertThatThrownBy(() -> assertAlreadyRecorded(Created))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("recognized as already recorded (Exists)")
                    .hasMessageContaining("the actual result is Created");
        }

        @Test
        @DisplayName("When checking a rejection reason, fails if the reason differs")
        void rejectedForChecksTheReason() {
            assertThatThrownBy(() -> assertRejectedFor(ExceedsDebits, ExceedsCredits))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ExceedsCredits")
                    .hasMessageContaining("the actual result is ExceedsDebits");
        }
    }

    @Nested
    @Order(2)
    @DisplayName("assertContentConflict, which checks a content conflict")
    class ContentConflict {

        @Test
        @DisplayName("Fails on a conflict on another field, naming the expected field and the actual code")
        void failsOnAConflictOnAnotherField() {
            assertThatThrownBy(() -> assertContentConflict(ExistsWithDifferentAmount, ExistsWithDifferentCreditAccountId))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("a rejection as a conflict because the content differs for the same id (ExistsWithDifferentCreditAccountId)")
                    .hasMessageContaining("the actual result is ExistsWithDifferentAmount");
        }

        @Test
        @DisplayName("Passing a code that is not a content conflict as the expectation is treated as misuse and stops")
        void refusesANonConflictCodeAsExpectation() {
            assertThatThrownBy(() -> assertContentConflict(Exists, Exists))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Exists is not a content conflict code (ExistsWithDifferent…)");
        }
    }
}
