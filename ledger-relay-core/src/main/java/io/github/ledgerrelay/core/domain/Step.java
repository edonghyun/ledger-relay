package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.LedgerResultCode;
import java.util.Optional;

/**
 * One thing that happens to a command row. A claim receives a token; every other write presents the token from the
 * claim.
 */
public sealed interface Step {

    /**
     * A claim. It presents no token.
     *
     * @param gate         the gate decision for an accepted (PENDING) command
     * @param leaseExpired whether the lease of an in-progress (CLAIMED) command has expired. The store decides by PG
     *                     time
     */
    record Claim(GateDecision gate, boolean leaseExpired) implements Step {
    }

    /**
     * An operator abandons a halted (HALTED) command. It presents no token; the service writes conditionally on the
     * token it read.
     */
    record Abandon() implements Step {
    }

    /** A write with the token from the claim. In jdbc it is {@code WHERE lease_token = ? AND state = 'CLAIMED'}. */
    sealed interface Write extends Step {
        long token();
    }

    /** Recording the submission right before sending to TB. */
    record MarkSubmitted(long token) implements Write {
    }

    /** Ends with the TB result. If a known result exists, it must be that code. */
    record Complete(long token, LedgerResultCode code) implements Write {
    }

    /** Skips because the referenced reservation ended without being applied. */
    record Skip(long token) implements Write {
    }

    /** Puts the command back after no response or an exception. */
    record Release(long token) implements Write {
    }

    /** The outcome handler failed. Passes the received TB result (none for a skip) to the next claim. */
    record HandlerFailed(long token, Optional<LedgerResultCode> knownResult) implements Write {
    }
}
