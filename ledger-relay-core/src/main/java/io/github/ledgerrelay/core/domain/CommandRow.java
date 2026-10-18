package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.SkipReason;
import java.util.Objects;
import java.util.Optional;

/**
 * The part of a command row that the state machine sees. The store handles the timestamp columns (lease_expires_at,
 * next_attempt_at, completed_at) with PG's now(). The state machine knows no clock.
 *
 * @param knownResult the TB result passed on to the next claim because the handler failed
 * @param resultCode  the TB result that ended the command. Empty if it ended as skipped
 */
public record CommandRow(
        StoredState state,
        long leaseToken,
        long submitCount,
        Optional<LedgerResultCode> knownResult,
        Optional<LedgerResultCode> resultCode,
        int handlerFailures,
        Optional<SkipReason> skipReason) {

    public CommandRow {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(knownResult, "knownResult");
        Objects.requireNonNull(resultCode, "resultCode");
        Objects.requireNonNull(skipReason, "skipReason");
        if (skipReason.isPresent() != (state == StoredState.SKIPPED)) {
            throw new IllegalArgumentException(
                    "skipReason must be present exactly when state is SKIPPED: state=" + state + ", skipReason=" + skipReason);
        }
    }

    /** A row that did not end as skipped. Used for SKIPPED it fails, because there is no reason. */
    public CommandRow(StoredState state, long leaseToken, long submitCount, Optional<LedgerResultCode> knownResult,
            Optional<LedgerResultCode> resultCode, int handlerFailures) {
        this(state, leaseToken, submitCount, knownResult, resultCode, handlerFailures, Optional.empty());
    }

    /** A row just accepted. */
    public static CommandRow accepted() {
        return new CommandRow(StoredState.PENDING, 0, 0, Optional.empty(), Optional.empty(), 0);
    }
}
