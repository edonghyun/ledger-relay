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
            assertContentConflict(ExistsWithDifferentCode, ExistsWithDifferentCode);
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

    @Nested
    @DisplayName("내용 충돌을 확인하는 assertContentConflict는")
    class ContentConflict {

        @Test
        @DisplayName("다른 필드의 충돌이면 기대한 필드와 실제 코드를 담아 실패한다")
        void failsOnAConflictOnAnotherField() {
            assertThatThrownBy(() -> assertContentConflict(ExistsWithDifferentAmount, ExistsWithDifferentCreditAccountId))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("같은 ID인데 내용이 달라 충돌로 거부되기(ExistsWithDifferentCreditAccountId)")
                    .hasMessageContaining("실제 결과는 ExistsWithDifferentAmount");
        }

        @Test
        @DisplayName("기대값으로 내용 충돌이 아닌 코드를 넘기면 잘못 쓴 것으로 보고 멈춘다")
        void refusesANonConflictCodeAsExpectation() {
            assertThatThrownBy(() -> assertContentConflict(Exists, Exists))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Exists는 내용 충돌 코드(ExistsWithDifferent…)가 아닙니다");
        }
    }
}
