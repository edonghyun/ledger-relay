package io.github.ledgerrelay.core;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TB {@code create_transfers} result codes (68 codes as of TB 0.17.9). The constant names match
 * {@code CreateTransferStatus} in the TB Java client. {@code ResultCodeGuardTest} in the tigerbeetle module checks
 * that the two enums are exactly the same.
 *
 * <p>The DB stores {@link #wireName()}. Constant names can change along with the TB client.
 *
 * <p>{@link #category()} is a switch without default. Adding a constant without a category fails compilation.
 */
public enum LedgerResultCode {
    Created("created"),
    LinkedEventFailed("linked_event_failed"),
    LinkedEventChainOpen("linked_event_chain_open"),
    ImportedEventExpected("imported_event_expected"),
    ImportedEventNotExpected("imported_event_not_expected"),
    TimestampMustBeZero("timestamp_must_be_zero"),
    ImportedEventTimestampOutOfRange("imported_event_timestamp_out_of_range"),
    ImportedEventTimestampMustNotAdvance("imported_event_timestamp_must_not_advance"),
    ReservedFlag("reserved_flag"),
    IdMustNotBeZero("id_must_not_be_zero"),
    IdMustNotBeIntMax("id_must_not_be_int_max"),
    ExistsWithDifferentFlags("exists_with_different_flags"),
    ExistsWithDifferentPendingId("exists_with_different_pending_id"),
    ExistsWithDifferentTimeout("exists_with_different_timeout"),
    ExistsWithDifferentDebitAccountId("exists_with_different_debit_account_id"),
    ExistsWithDifferentCreditAccountId("exists_with_different_credit_account_id"),
    ExistsWithDifferentAmount("exists_with_different_amount"),
    ExistsWithDifferentUserData128("exists_with_different_user_data_128"),
    ExistsWithDifferentUserData64("exists_with_different_user_data_64"),
    ExistsWithDifferentUserData32("exists_with_different_user_data_32"),
    ExistsWithDifferentLedger("exists_with_different_ledger"),
    ExistsWithDifferentCode("exists_with_different_code"),
    Exists("exists"),
    IdAlreadyFailed("id_already_failed"),
    FlagsAreMutuallyExclusive("flags_are_mutually_exclusive"),
    DebitAccountIdMustNotBeZero("debit_account_id_must_not_be_zero"),
    DebitAccountIdMustNotBeIntMax("debit_account_id_must_not_be_int_max"),
    CreditAccountIdMustNotBeZero("credit_account_id_must_not_be_zero"),
    CreditAccountIdMustNotBeIntMax("credit_account_id_must_not_be_int_max"),
    AccountsMustBeDifferent("accounts_must_be_different"),
    PendingIdMustBeZero("pending_id_must_be_zero"),
    PendingIdMustNotBeZero("pending_id_must_not_be_zero"),
    PendingIdMustNotBeIntMax("pending_id_must_not_be_int_max"),
    PendingIdMustBeDifferent("pending_id_must_be_different"),
    TimeoutReservedForPendingTransfer("timeout_reserved_for_pending_transfer"),
    ClosingTransferMustBePending("closing_transfer_must_be_pending"),
    LedgerMustNotBeZero("ledger_must_not_be_zero"),
    CodeMustNotBeZero("code_must_not_be_zero"),
    DebitAccountNotFound("debit_account_not_found"),
    CreditAccountNotFound("credit_account_not_found"),
    AccountsMustHaveTheSameLedger("accounts_must_have_the_same_ledger"),
    TransferMustHaveTheSameLedgerAsAccounts("transfer_must_have_the_same_ledger_as_accounts"),
    PendingTransferNotFound("pending_transfer_not_found"),
    PendingTransferNotPending("pending_transfer_not_pending"),
    PendingTransferHasDifferentDebitAccountId("pending_transfer_has_different_debit_account_id"),
    PendingTransferHasDifferentCreditAccountId("pending_transfer_has_different_credit_account_id"),
    PendingTransferHasDifferentLedger("pending_transfer_has_different_ledger"),
    PendingTransferHasDifferentCode("pending_transfer_has_different_code"),
    ExceedsPendingTransferAmount("exceeds_pending_transfer_amount"),
    PendingTransferHasDifferentAmount("pending_transfer_has_different_amount"),
    PendingTransferAlreadyPosted("pending_transfer_already_posted"),
    PendingTransferAlreadyVoided("pending_transfer_already_voided"),
    PendingTransferExpired("pending_transfer_expired"),
    ImportedEventTimestampMustNotRegress("imported_event_timestamp_must_not_regress"),
    ImportedEventTimestampMustPostdateDebitAccount("imported_event_timestamp_must_postdate_debit_account"),
    ImportedEventTimestampMustPostdateCreditAccount("imported_event_timestamp_must_postdate_credit_account"),
    ImportedEventTimeoutMustBeZero("imported_event_timeout_must_be_zero"),
    DebitAccountAlreadyClosed("debit_account_already_closed"),
    CreditAccountAlreadyClosed("credit_account_already_closed"),
    OverflowsDebitsPending("overflows_debits_pending"),
    OverflowsCreditsPending("overflows_credits_pending"),
    OverflowsDebitsPosted("overflows_debits_posted"),
    OverflowsCreditsPosted("overflows_credits_posted"),
    OverflowsDebits("overflows_debits"),
    OverflowsCredits("overflows_credits"),
    OverflowsTimeout("overflows_timeout"),
    ExceedsCredits("exceeds_credits"),
    ExceedsDebits("exceeds_debits");

    private static final Map<String, LedgerResultCode> BY_WIRE_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(LedgerResultCode::wireName, code -> code));

    private final String wireName;

    LedgerResultCode(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The name stored in the DB ({@code result_code}, {@code known_result}). The snake_case name from the TB source; it
     * never changes.
     */
    public String wireName() {
        return wireName;
    }

    /** Finds the code by its stored name. It does not accept the Java constant name ({@code ExceedsCredits}). */
    public static LedgerResultCode fromWireName(String wireName) {
        LedgerResultCode code = wireName == null ? null : BY_WIRE_NAME.get(wireName);
        if (code == null) {
            throw new IllegalArgumentException("Unknown stored result code name: " + wireName);
        }
        return code;
    }

    public ResultCategory category() {
        return switch (this) {
            case Created, Exists -> ResultCategory.APPLIED;

            case ExistsWithDifferentFlags, ExistsWithDifferentPendingId, ExistsWithDifferentTimeout,
                 ExistsWithDifferentDebitAccountId, ExistsWithDifferentCreditAccountId, ExistsWithDifferentAmount,
                 ExistsWithDifferentUserData128, ExistsWithDifferentUserData64, ExistsWithDifferentUserData32,
                 ExistsWithDifferentLedger, ExistsWithDifferentCode -> ResultCategory.CONFLICT;

            case ExceedsCredits, ExceedsDebits, DebitAccountAlreadyClosed, CreditAccountAlreadyClosed,
                 PendingTransferExpired, PendingTransferAlreadyPosted, PendingTransferAlreadyVoided,
                 OverflowsDebitsPending, OverflowsCreditsPending, OverflowsDebitsPosted, OverflowsCreditsPosted,
                 OverflowsDebits, OverflowsCredits -> ResultCategory.BUSINESS;

            case IdAlreadyFailed -> ResultCategory.PRIOR_FAILURE;

            case DebitAccountNotFound, CreditAccountNotFound, PendingTransferNotFound, PendingTransferNotPending,
                 IdMustNotBeZero, IdMustNotBeIntMax, DebitAccountIdMustNotBeZero, DebitAccountIdMustNotBeIntMax,
                 CreditAccountIdMustNotBeZero, CreditAccountIdMustNotBeIntMax, LedgerMustNotBeZero,
                 CodeMustNotBeZero, AccountsMustBeDifferent, PendingIdMustBeZero, PendingIdMustNotBeZero,
                 PendingIdMustNotBeIntMax, PendingIdMustBeDifferent, AccountsMustHaveTheSameLedger,
                 TransferMustHaveTheSameLedgerAsAccounts, PendingTransferHasDifferentDebitAccountId,
                 PendingTransferHasDifferentCreditAccountId, PendingTransferHasDifferentLedger,
                 PendingTransferHasDifferentCode, PendingTransferHasDifferentAmount,
                 TimeoutReservedForPendingTransfer, ClosingTransferMustBePending, ReservedFlag,
                 FlagsAreMutuallyExclusive, TimestampMustBeZero,
                 // The reserved amount never changes, so the app knows it in advance. A timeout overflow before 2126 is
                 // a bug or a clock fault
                 ExceedsPendingTransferAmount, OverflowsTimeout -> ResultCategory.INVALID;

            case LinkedEventFailed, LinkedEventChainOpen, ImportedEventExpected, ImportedEventNotExpected,
                 ImportedEventTimestampOutOfRange, ImportedEventTimestampMustNotAdvance,
                 ImportedEventTimestampMustNotRegress, ImportedEventTimestampMustPostdateDebitAccount,
                 ImportedEventTimestampMustPostdateCreditAccount, ImportedEventTimeoutMustBeZero
                    -> ResultCategory.UNSUPPORTED;
        };
    }

}
