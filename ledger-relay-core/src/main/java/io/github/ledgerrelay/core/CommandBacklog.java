package io.github.ledgerrelay.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The commands waiting right now ({@link LedgerRelayOperator#backlog()}). Counts are exact. Finished commands are not
 * counted; count them from {@link LedgerOutcomeHandler} calls or from metrics.
 *
 * @param pending          accepted and not yet taken by a worker
 * @param claimed          taken by a worker: about to be sent to TB, or sent and waiting for the result
 * @param halted           halted. They block their ordering key until an operator resolves them
 * @param oldestWaitingAge how long the oldest pending or claimed command has waited since acceptance. Empty when none
 *                         waits
 * @param readAt           when the counts were read, on the database clock
 */
public record CommandBacklog(long pending, long claimed, long halted, Optional<Duration> oldestWaitingAge,
        Instant readAt) {

    public CommandBacklog {
        Objects.requireNonNull(oldestWaitingAge, "oldestWaitingAge");
        Objects.requireNonNull(readAt, "readAt");
    }
}
