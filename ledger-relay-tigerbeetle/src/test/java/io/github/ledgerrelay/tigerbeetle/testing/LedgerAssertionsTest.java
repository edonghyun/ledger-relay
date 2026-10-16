package io.github.ledgerrelay.tigerbeetle.testing;

import static com.tigerbeetle.CreateTransferStatus.Created;
import static com.tigerbeetle.CreateTransferStatus.ExceedsCredits;
import static com.tigerbeetle.CreateTransferStatus.ExceedsDebits;
import static com.tigerbeetle.CreateTransferStatus.Exists;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentAmount;
import static com.tigerbeetle.CreateTransferStatus.IdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertAlreadyRecorded;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertConflictOnAmount;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedBecauseIdAlreadyFailed;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertRejectedFor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("결과 확인 어휘 — docs/testing.md §5")
class LedgerAssertionsTest {

    @Nested
    @DisplayName("결과 코드를 확인하는 assert는")
    class ResultAssertions {

        @Test
        @DisplayName("기대한 결과면 조용히 통과한다")
        void passesOnTheExpectedResult() {
            assertCreated(Created);
            assertAlreadyRecorded(Exists);
            assertConflictOnAmount(ExistsWithDifferentAmount);
            assertRejectedBecauseIdAlreadyFailed(IdAlreadyFailed);
            assertRejectedFor(ExceedsCredits, ExceedsCredits);
        }

        @Test
        @DisplayName("다른 결과면 기대한 뜻과 TB 코드, 실제 코드를 함께 담아 실패한다")
        void failsWithMeaningAndCodes() {
            assertThatThrownBy(() -> assertAlreadyRecorded(Created))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("이미 기록된 것으로 인정되기(Exists)")
                    .hasMessageContaining("실제 결과는 Created");
        }

        @Test
        @DisplayName("거부 사유를 확인할 때 사유가 다르면 실패한다")
        void rejectedForChecksTheReason() {
            assertThatThrownBy(() -> assertRejectedFor(ExceedsDebits, ExceedsCredits))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ExceedsCredits")
                    .hasMessageContaining("실제 결과는 ExceedsDebits");
        }
    }
}
