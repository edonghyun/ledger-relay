package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("결과 코드의 저장 이름 — 스펙 §5.6 result_code·known_result 컬럼")
class ResultCodeWireNameTest {

    /**
     * TB 0.17.9 소스 {@code tigerbeetle.zig}의 {@code CreateTransferStatus} 이름을 그대로 옮긴 것.
     * 이 이름은 DB에 저장되므로 한 번 정하면 바꾸지 않는다. 바꾸면 이미 저장된 행을 읽지 못한다.
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
    @DisplayName("코드마다")
    class EveryCode {

        @ParameterizedTest(name = "{0} ↔ {1}", quoteTextArguments = false)
        @CsvSource(textBlock = WIRE_NAME_TABLE)
        @DisplayName("TB 문서의 snake_case 이름으로 저장되고, 그 이름으로 다시 읽힌다")
        void storesUnderItsTigerBeetleName(LedgerResultCode code, String wireName) {
            // when
            String stored = code.wireName();

            // then
            assertThat(stored).as("%s의 저장 이름", code).isEqualTo(wireName);
            assertThat(LedgerResultCode.fromWireName(wireName)).as("저장 이름 %s를 읽은 코드", wireName).isEqualTo(code);
        }

        @Test
        @DisplayName("저장 이름 표는 모든 코드를 정확히 한 번씩 담고, 이름이 겹치지 않는다")
        void tableListsEveryCodeOnce() {
            // given
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
    @DisplayName("저장 이름이 아닌 문자열을 읽으면")
    class UnknownNames {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"ExceedsCredits", "EXCEEDS_CREDITS", "exceeds_credit", "", "deprecated_18"})
        @DisplayName("코드로 바꾸지 않고 거부한다 — Java 상수 이름이나 비슷한 철자를 저장 이름으로 받지 않는다")
        void refusesAnythingButAWireName(String notAWireName) {
            assertThatThrownBy(() -> LedgerResultCode.fromWireName(notAWireName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("알 수 없는 결과 코드 저장 이름");
        }

        @Test
        @DisplayName("null도 같은 이유로 거부한다")
        void refusesNull() {
            assertThatThrownBy(() -> LedgerResultCode.fromWireName(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("알 수 없는 결과 코드 저장 이름");
        }
    }
}
