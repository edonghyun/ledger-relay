package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TB result classification — the classification table")
class ResultClassificationTest {

    /** A direct copy of the classification table. The expected values are written by hand. */
    static final String CLASSIFICATION_TABLE = """
            # Applied
            Created, APPLIED
            Exists, APPLIED
            # Conflict: same id with different content (11 codes)
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
            # Rejected, business
            ExceedsCredits, BUSINESS
            ExceedsDebits, BUSINESS
            DebitAccountAlreadyClosed, BUSINESS
            CreditAccountAlreadyClosed, BUSINESS
            PendingTransferExpired, BUSINESS
            PendingTransferAlreadyPosted, BUSINESS
            PendingTransferAlreadyVoided, BUSINESS
            OverflowsDebitsPending, BUSINESS
            OverflowsCreditsPending, BUSINESS
            OverflowsDebitsPosted, BUSINESS
            OverflowsCreditsPosted, BUSINESS
            OverflowsDebits, BUSINESS
            OverflowsCredits, BUSINESS
            # Rejected, prior failure: the first attempt, whose response was lost, hit one of TB's 7 transient failures. It may also have been a missing account (INVALID), so alert
            IdAlreadyFailed, PRIOR_FAILURE
            # Rejected, invalid request: an app bug or a configuration error
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
            # The reservation amount does not change, so the app knows it in advance. Deterministic and does not use up the id — ReservationSemanticsTest chapters 2 and 3
            ExceedsPendingTransferAmount, INVALID
            # Occurs only when timestamp + timeout exceeds 2^63 ns (year 2262). timeout is at most 2^32 s (136 years), so before 2126 it means a bug or a broken clock
            OverflowsTimeout, INVALID
            # Not applicable in v1: linked chains and imported events
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
    @Order(1)
    @DisplayName("When a TB result code comes back")
    class Codes {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = CLASSIFICATION_TABLE)
        @DisplayName("It falls into the category written in the classification table")
        void fallsIntoItsCategory(LedgerResultCode code, ResultCategory expected) {
            assertFallsInto(code, expected);
        }

        @Test
        @DisplayName("The classification table lists every code exactly once — so no code is missing from the table")
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
    @Order(2)
    @DisplayName("Once the category is decided")
    class Categories {

        @ParameterizedTest(name = "{0} → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                APPLIED,       false, does not alert
                CONFLICT,      true,  alerts a person
                BUSINESS,      false, does not alert
                PRIOR_FAILURE, true,  alerts a person
                INVALID,       true,  alerts a person
                UNSUPPORTED,   true,  alerts a person
                """)
        @DisplayName("It decides whether to alert a person")
        void decidesWhetherToAlert(ResultCategory category, boolean alerts, String meaning) {
            assertAlerts(category, alerts);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    private static void assertFallsInto(LedgerResultCode code, ResultCategory expected) {
        assertThat(code.category()).as("%s falls into %s of the classification table", code, expected).isEqualTo(expected);
    }

    private static void assertAlerts(ResultCategory category, boolean expected) {
        assertThat(category.alerts()).as("category %s %s", category, expected ? "alerts" : "does not alert")
                .isEqualTo(expected);
    }
}
