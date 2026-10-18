package io.github.ledgerrelay.core;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TB {@code create_transfers} 결과 코드(TB 0.17.9 기준 68종). 상수 이름은 TB Java 클라이언트의
 * {@code CreateTransferStatus}와 같다. 두 enum이 정확히 같은지는 tigerbeetle 모듈의
 * {@code ResultCodeGuardTest}가 확인한다.
 *
 * <p>DB에는 {@link #wireName()}을 저장한다. 상수 이름은 TB 클라이언트를 따라 바뀔 수 있다.
 *
 * <p>{@link #category()}는 default 없는 switch다. 상수를 추가하고 분류를 빠뜨리면 컴파일이 실패한다.
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

    /** DB({@code result_code}, {@code known_result})에 저장하는 이름. TB 소스의 snake_case 이름이며 바꾸지 않는다. */
    public String wireName() {
        return wireName;
    }

    /** 저장 이름으로 코드를 찾는다. Java 상수 이름({@code ExceedsCredits})은 받지 않는다. */
    public static LedgerResultCode fromWireName(String wireName) {
        LedgerResultCode code = wireName == null ? null : BY_WIRE_NAME.get(wireName);
        if (code == null) {
            throw new IllegalArgumentException("알 수 없는 결과 코드 저장 이름: " + wireName);
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
                 // 예약 금액은 바뀌지 않아 앱이 미리 안다. 2126년 전의 timeout 넘침은 버그나 시계 이상이다
                 ExceedsPendingTransferAmount, OverflowsTimeout -> ResultCategory.INVALID;

            case LinkedEventFailed, LinkedEventChainOpen, ImportedEventExpected, ImportedEventNotExpected,
                 ImportedEventTimestampOutOfRange, ImportedEventTimestampMustNotAdvance,
                 ImportedEventTimestampMustNotRegress, ImportedEventTimestampMustPostdateDebitAccount,
                 ImportedEventTimestampMustPostdateCreditAccount, ImportedEventTimeoutMustBeZero
                    -> ResultCategory.UNSUPPORTED;
        };
    }

}
