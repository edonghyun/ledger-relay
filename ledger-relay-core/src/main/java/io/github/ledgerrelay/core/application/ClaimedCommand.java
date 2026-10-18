package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.Submission;
import java.util.Objects;
import java.util.Optional;

/**
 * One command taken by a claim. The worker uses it to choose the next step.
 * If {@link #skip()}, it calls {@code skip} without sending to TB. If {@link #knownResult()} is present, it calls
 * {@code complete} with that result without sending to TB. Otherwise it calls {@code markSubmitted} and then sends to
 * TB.
 *
 * @param leaseToken the token this claim received. Every later write presents it
 * @param skip       the reservation that the post or void refers to ended without being applied
 */
public record ClaimedCommand(
        long rowId,
        String commandId,
        String orderingKey,
        long leaseToken,
        boolean skip,
        Optional<LedgerResultCode> knownResult,
        Submission submission) {

    public ClaimedCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(knownResult, "knownResult");
        Objects.requireNonNull(submission, "submission");
    }
}
