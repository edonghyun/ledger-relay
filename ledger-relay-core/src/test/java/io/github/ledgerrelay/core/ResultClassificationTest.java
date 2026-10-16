package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TB 결과 분류 — 스펙 §4.4 분류표")
class ResultClassificationTest {

    /** 스펙 §4.4 분류표를 그대로 옮긴 것. 기대값은 손으로 쓴다. */
    static final String CLASSIFICATION_TABLE = """
            # 적용됨
            Created, APPLIED
            Exists, APPLIED
            # 충돌: 같은 ID에 다른 내용 (11종)
            ExistsWithDifferentFlags, CONFLICT
            ExistsWithDifferentPendingId, CONFLICT
            ExistsWithDifferentTimeout, CONFLICT
            ExistsWithDifferentDebitAccountId, CONFLICT
            ExistsWithDifferentCreditAccountId, CONFLICT
            ExistsWithDifferentAmount, CONFLICT
            ExistsWithDifferentUserData128, CONFLICT
            ExistsWithDifferentUserData64, CONFLICT
            ExistsWithDifferentUserData32, CONFLICT
            ExistsWithDifferentLedger, CONFLICT
            ExistsWithDifferentCode, CONFLICT
            # 거절·업무
            ExceedsCredits, BUSINESS
            ExceedsDebits, BUSINESS
            DebitAccountAlreadyClosed, BUSINESS
            CreditAccountAlreadyClosed, BUSINESS
            PendingTransferExpired, BUSINESS
            PendingTransferAlreadyPosted, BUSINESS
            PendingTransferAlreadyVoided, BUSINESS
            ExceedsPendingTransferAmount, BUSINESS
            OverflowsDebitsPending, BUSINESS
            OverflowsCreditsPending, BUSINESS
            OverflowsDebitsPosted, BUSINESS
            OverflowsCreditsPosted, BUSINESS
            OverflowsDebits, BUSINESS
            OverflowsCredits, BUSINESS
            OverflowsTimeout, BUSINESS
            # 거절·이전 실패: 응답이 유실된 첫 시도가 TB의 일시적 실패 7종 중 하나였다. 계좌 없음(INVALID)일 수도 있어 알린다
            IdAlreadyFailed, PRIOR_FAILURE
            # 거절·잘못된 요청: 앱 버그 또는 설정 오류
            DebitAccountNotFound, INVALID
            CreditAccountNotFound, INVALID
            PendingTransferNotFound, INVALID
            PendingTransferNotPending, INVALID
            IdMustNotBeZero, INVALID
            IdMustNotBeIntMax, INVALID
            DebitAccountIdMustNotBeZero, INVALID
            DebitAccountIdMustNotBeIntMax, INVALID
            CreditAccountIdMustNotBeZero, INVALID
            CreditAccountIdMustNotBeIntMax, INVALID
            LedgerMustNotBeZero, INVALID
            CodeMustNotBeZero, INVALID
            AccountsMustBeDifferent, INVALID
            PendingIdMustBeZero, INVALID
            PendingIdMustNotBeZero, INVALID
            PendingIdMustNotBeIntMax, INVALID
            PendingIdMustBeDifferent, INVALID
            AccountsMustHaveTheSameLedger, INVALID
            TransferMustHaveTheSameLedgerAsAccounts, INVALID
            PendingTransferHasDifferentDebitAccountId, INVALID
            PendingTransferHasDifferentCreditAccountId, INVALID
            PendingTransferHasDifferentLedger, INVALID
            PendingTransferHasDifferentCode, INVALID
            PendingTransferHasDifferentAmount, INVALID
            TimeoutReservedForPendingTransfer, INVALID
            ClosingTransferMustBePending, INVALID
            ReservedFlag, INVALID
            FlagsAreMutuallyExclusive, INVALID
            TimestampMustBeZero, INVALID
            # v1 해당 없음: linked chain과 imported 이벤트
            LinkedEventFailed, UNSUPPORTED
            LinkedEventChainOpen, UNSUPPORTED
            ImportedEventExpected, UNSUPPORTED
            ImportedEventNotExpected, UNSUPPORTED
            ImportedEventTimestampOutOfRange, UNSUPPORTED
            ImportedEventTimestampMustNotAdvance, UNSUPPORTED
            ImportedEventTimestampMustNotRegress, UNSUPPORTED
            ImportedEventTimestampMustPostdateDebitAccount, UNSUPPORTED
            ImportedEventTimestampMustPostdateCreditAccount, UNSUPPORTED
            ImportedEventTimeoutMustBeZero, UNSUPPORTED
            """;

    @Nested
    @DisplayName("TB 결과 코드는")
    class Codes {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = CLASSIFICATION_TABLE)
        @DisplayName("분류표에 적힌 분류로 나뉜다")
        void fallsIntoItsCategory(LedgerResultCode code, ResultCategory expected) {
            assertThat(code.category()).as("%s의 분류", code).isEqualTo(expected);
        }

        @Test
        @DisplayName("분류표는 모든 코드를 정확히 한 번씩 담는다 — 표에서 빠진 코드가 없게")
        void tableListsEveryCodeOnce() {
            // given
            List<String> codesInTheTable = CLASSIFICATION_TABLE.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split(",")[0].strip())
                    .toList();

            // then
            assertThat(codesInTheTable)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(LedgerResultCode.values()).map(Enum::name).toList());
        }
    }

    @Nested
    @DisplayName("분류마다")
    class Categories {

        @ParameterizedTest(name = "{0} → 상태 {1}, 알림 {2}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                APPLIED,       APPLIED,  false
                CONFLICT,      CONFLICT, true
                BUSINESS,      REJECTED, false
                PRIOR_FAILURE, REJECTED, true
                INVALID,       REJECTED, true
                UNSUPPORTED,   CONFLICT, true
                """)
        @DisplayName("명령이 끝나는 상태와 알림 여부가 정해진다")
        void decidesFinalStateAndAlert(ResultCategory category, CommandState state, boolean alerts) {
            assertThat(category.finalState()).as("%s의 최종 상태", category).isEqualTo(state);
            assertThat(category.alerts()).as("%s의 알림 여부", category).isEqualTo(alerts);
        }
    }
}
