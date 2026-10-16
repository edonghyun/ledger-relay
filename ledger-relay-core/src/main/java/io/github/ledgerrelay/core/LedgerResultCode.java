package io.github.ledgerrelay.core;

/**
 * TB {@code create_transfers} 결과 코드(TB 0.17.9 기준 68종). 상수 이름은 TB Java 클라이언트의
 * {@code CreateTransferStatus}와 같다(P1-9). 두 enum이 정확히 같은지는 tigerbeetle 모듈의
 * {@code ResultCodeGuardTest}가 확인한다.
 *
 * <p>{@link #category()}는 default 없는 switch다. 상수를 추가하고 분류를 빠뜨리면 컴파일이 실패한다.
 */
public enum LedgerResultCode {
    Created,
    LinkedEventFailed,
    LinkedEventChainOpen,
    ImportedEventExpected,
    ImportedEventNotExpected,
    TimestampMustBeZero,
    ImportedEventTimestampOutOfRange,
    ImportedEventTimestampMustNotAdvance,
    ReservedFlag,
    IdMustNotBeZero,
    IdMustNotBeIntMax,
    ExistsWithDifferentFlags,
    ExistsWithDifferentPendingId,
    ExistsWithDifferentTimeout,
    ExistsWithDifferentDebitAccountId,
    ExistsWithDifferentCreditAccountId,
    ExistsWithDifferentAmount,
    ExistsWithDifferentUserData128,
    ExistsWithDifferentUserData64,
    ExistsWithDifferentUserData32,
    ExistsWithDifferentLedger,
    ExistsWithDifferentCode,
    Exists,
    IdAlreadyFailed,
    FlagsAreMutuallyExclusive,
    DebitAccountIdMustNotBeZero,
    DebitAccountIdMustNotBeIntMax,
    CreditAccountIdMustNotBeZero,
    CreditAccountIdMustNotBeIntMax,
    AccountsMustBeDifferent,
    PendingIdMustBeZero,
    PendingIdMustNotBeZero,
    PendingIdMustNotBeIntMax,
    PendingIdMustBeDifferent,
    TimeoutReservedForPendingTransfer,
    ClosingTransferMustBePending,
    LedgerMustNotBeZero,
    CodeMustNotBeZero,
    DebitAccountNotFound,
    CreditAccountNotFound,
    AccountsMustHaveTheSameLedger,
    TransferMustHaveTheSameLedgerAsAccounts,
    PendingTransferNotFound,
    PendingTransferNotPending,
    PendingTransferHasDifferentDebitAccountId,
    PendingTransferHasDifferentCreditAccountId,
    PendingTransferHasDifferentLedger,
    PendingTransferHasDifferentCode,
    ExceedsPendingTransferAmount,
    PendingTransferHasDifferentAmount,
    PendingTransferAlreadyPosted,
    PendingTransferAlreadyVoided,
    PendingTransferExpired,
    ImportedEventTimestampMustNotRegress,
    ImportedEventTimestampMustPostdateDebitAccount,
    ImportedEventTimestampMustPostdateCreditAccount,
    ImportedEventTimeoutMustBeZero,
    DebitAccountAlreadyClosed,
    CreditAccountAlreadyClosed,
    OverflowsDebitsPending,
    OverflowsCreditsPending,
    OverflowsDebitsPosted,
    OverflowsCreditsPosted,
    OverflowsDebits,
    OverflowsCredits,
    OverflowsTimeout,
    ExceedsCredits,
    ExceedsDebits;

    public ResultCategory category() {
        return switch (this) {
            case Created, Exists -> ResultCategory.APPLIED;

            case ExistsWithDifferentFlags, ExistsWithDifferentPendingId, ExistsWithDifferentTimeout,
                 ExistsWithDifferentDebitAccountId, ExistsWithDifferentCreditAccountId, ExistsWithDifferentAmount,
                 ExistsWithDifferentUserData128, ExistsWithDifferentUserData64, ExistsWithDifferentUserData32,
                 ExistsWithDifferentLedger, ExistsWithDifferentCode -> ResultCategory.CONFLICT;

            case ExceedsCredits, ExceedsDebits, DebitAccountAlreadyClosed, CreditAccountAlreadyClosed,
                 PendingTransferExpired, PendingTransferAlreadyPosted, PendingTransferAlreadyVoided,
                 ExceedsPendingTransferAmount, OverflowsDebitsPending, OverflowsCreditsPending,
                 OverflowsDebitsPosted, OverflowsCreditsPosted, OverflowsDebits, OverflowsCredits,
                 OverflowsTimeout -> ResultCategory.BUSINESS;

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
                 FlagsAreMutuallyExclusive, TimestampMustBeZero -> ResultCategory.INVALID;

            case LinkedEventFailed, LinkedEventChainOpen, ImportedEventExpected, ImportedEventNotExpected,
                 ImportedEventTimestampOutOfRange, ImportedEventTimestampMustNotAdvance,
                 ImportedEventTimestampMustNotRegress, ImportedEventTimestampMustPostdateDebitAccount,
                 ImportedEventTimestampMustPostdateCreditAccount, ImportedEventTimeoutMustBeZero
                    -> ResultCategory.UNSUPPORTED;
        };
    }
}
