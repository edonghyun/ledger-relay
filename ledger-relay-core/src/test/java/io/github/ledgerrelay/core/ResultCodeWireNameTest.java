package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Stored names of result codes — result_code and known_result columns")
class ResultCodeWireNameTest {

    /**
     * A direct copy of the {@code CreateTransferStatus} names in the TB 0.17.9 source {@code tigerbeetle.zig}.
     * These names are stored in the DB, so once chosen they never change. Changing one makes stored rows unreadable.
     */
    static final String WIRE_NAME_TABLE = """
            Created, created
            LinkedEventFailed, linked_event_failed
            LinkedEventChainOpen, linked_event_chain_open
            ImportedEventExpected, imported_event_expected
            ImportedEventNotExpected, imported_event_not_expected
            TimestampMustBeZero, timestamp_must_be_zero
            ImportedEventTimestampOutOfRange, imported_event_timestamp_out_of_range
            ImportedEventTimestampMustNotAdvance, imported_event_timestamp_must_not_advance
            ReservedFlag, reserved_flag
            IdMustNotBeZero, id_must_not_be_zero
            IdMustNotBeIntMax, id_must_not_be_int_max
            ExistsWithDifferentFlags, exists_with_different_flags
            ExistsWithDifferentPendingId, exists_with_different_pending_id
            ExistsWithDifferentTimeout, exists_with_different_timeout
            ExistsWithDifferentDebitAccountId, exists_with_different_debit_account_id
            ExistsWithDifferentCreditAccountId, exists_with_different_credit_account_id
            ExistsWithDifferentAmount, exists_with_different_amount
            ExistsWithDifferentUserData128, exists_with_different_user_data_128
            ExistsWithDifferentUserData64, exists_with_different_user_data_64
            ExistsWithDifferentUserData32, exists_with_different_user_data_32
            ExistsWithDifferentLedger, exists_with_different_ledger
            ExistsWithDifferentCode, exists_with_different_code
            Exists, exists
            IdAlreadyFailed, id_already_failed
            FlagsAreMutuallyExclusive, flags_are_mutually_exclusive
            DebitAccountIdMustNotBeZero, debit_account_id_must_not_be_zero
            DebitAccountIdMustNotBeIntMax, debit_account_id_must_not_be_int_max
            CreditAccountIdMustNotBeZero, credit_account_id_must_not_be_zero
            CreditAccountIdMustNotBeIntMax, credit_account_id_must_not_be_int_max
            AccountsMustBeDifferent, accounts_must_be_different
            PendingIdMustBeZero, pending_id_must_be_zero
            PendingIdMustNotBeZero, pending_id_must_not_be_zero
            PendingIdMustNotBeIntMax, pending_id_must_not_be_int_max
            PendingIdMustBeDifferent, pending_id_must_be_different
            TimeoutReservedForPendingTransfer, timeout_reserved_for_pending_transfer
            ClosingTransferMustBePending, closing_transfer_must_be_pending
            LedgerMustNotBeZero, ledger_must_not_be_zero
            CodeMustNotBeZero, code_must_not_be_zero
            DebitAccountNotFound, debit_account_not_found
            CreditAccountNotFound, credit_account_not_found
            AccountsMustHaveTheSameLedger, accounts_must_have_the_same_ledger
            TransferMustHaveTheSameLedgerAsAccounts, transfer_must_have_the_same_ledger_as_accounts
            PendingTransferNotFound, pending_transfer_not_found
            PendingTransferNotPending, pending_transfer_not_pending
            PendingTransferHasDifferentDebitAccountId, pending_transfer_has_different_debit_account_id
            PendingTransferHasDifferentCreditAccountId, pending_transfer_has_different_credit_account_id
            PendingTransferHasDifferentLedger, pending_transfer_has_different_ledger
            PendingTransferHasDifferentCode, pending_transfer_has_different_code
            ExceedsPendingTransferAmount, exceeds_pending_transfer_amount
            PendingTransferHasDifferentAmount, pending_transfer_has_different_amount
            PendingTransferAlreadyPosted, pending_transfer_already_posted
            PendingTransferAlreadyVoided, pending_transfer_already_voided
            PendingTransferExpired, pending_transfer_expired
            ImportedEventTimestampMustNotRegress, imported_event_timestamp_must_not_regress
            ImportedEventTimestampMustPostdateDebitAccount, imported_event_timestamp_must_postdate_debit_account
            ImportedEventTimestampMustPostdateCreditAccount, imported_event_timestamp_must_postdate_credit_account
            ImportedEventTimeoutMustBeZero, imported_event_timeout_must_be_zero
            DebitAccountAlreadyClosed, debit_account_already_closed
            CreditAccountAlreadyClosed, credit_account_already_closed
            OverflowsDebitsPending, overflows_debits_pending
            OverflowsCreditsPending, overflows_credits_pending
            OverflowsDebitsPosted, overflows_debits_posted
            OverflowsCreditsPosted, overflows_credits_posted
            OverflowsDebits, overflows_debits
            OverflowsCredits, overflows_credits
            OverflowsTimeout, overflows_timeout
            ExceedsCredits, exceeds_credits
            ExceedsDebits, exceeds_debits
            """;

    @Nested
    @Order(1)
    @DisplayName("When a result code is stored")
    class StoringACode {

        @ParameterizedTest(name = "{0} ↔ {1}", quoteTextArguments = false)
        @CsvSource(textBlock = WIRE_NAME_TABLE)
        @DisplayName("It is stored under the snake_case name from the TB docs and read back from that name")
        void storesUnderItsTigerBeetleName(LedgerResultCode code, String wireName) {
            // when
            String stored = code.wireName();

            // then
            assertThat(stored).as("stored name of %s", code).isEqualTo(wireName);
            assertThat(LedgerResultCode.fromWireName(wireName)).as("code read from stored name %s", wireName).isEqualTo(code);
        }

        @Test
        @DisplayName("The stored-name table lists every code exactly once, and no two names collide")
        void tableListsEveryCodeOnce() {
            // when
            List<String[]> rows = WIRE_NAME_TABLE.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.split(",\\s*"))
                    .toList();

            // then
            assertThat(rows.stream().map(row -> row[0]).toList())
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(LedgerResultCode.values()).map(Enum::name).toList());
            assertThat(rows.stream().map(row -> row[1]).toList()).doesNotHaveDuplicates();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a string that is not a stored name is read")
    class UnknownNames {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"ExceedsCredits", "EXCEEDS_CREDITS", "exceeds_credit", "", "deprecated_18"})
        @DisplayName("It is refused instead of turned into a code — Java constant names and near spellings are not accepted as stored names")
        void refusesAnythingButAWireName(String notAWireName) {
            // when
            ThrowingCallable reading = () -> LedgerResultCode.fromWireName(notAWireName);

            // then
            assertRefusedAsUnknownWireName(reading);
        }

        @Test
        @DisplayName("null is refused for the same reason")
        void refusesNull() {
            // when
            ThrowingCallable reading = () -> LedgerResultCode.fromWireName(null);

            // then
            assertRefusedAsUnknownWireName(reading);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    private static void assertRefusedAsUnknownWireName(ThrowingCallable reading) {
        assertThatThrownBy(reading)
                .as("expected refusal because it is not a stored name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stored result code name");
    }
}
