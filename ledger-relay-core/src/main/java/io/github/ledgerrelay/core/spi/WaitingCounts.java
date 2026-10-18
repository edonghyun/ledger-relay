package io.github.ledgerrelay.core.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The exact number of commands waiting right now, by stored state (metrics and the operator's backlog). Unlike
 * {@link Backlog} there is no cap: the cost grows with the number of waiting commands, not with finished history.
 *
 * @param pending                  accepted and not claimed
 * @param claimed                  claimed by a worker: about to be sent, or sent and waiting for the result
 * @param halted                   halted. An operator must resolve them
 * @param oldestWaitingAcceptedAt  the acceptance time of the oldest pending or claimed command. Halted commands are
 *                                 not included
 * @param readAt                   the store's clock when it counted, so the age is measured on the same clock as the
 *                                 acceptance times
 */
public record WaitingCounts(long pending, long claimed, long halted, Optional<Instant> oldestWaitingAcceptedAt,
        Instant readAt) {

    public WaitingCounts {
        Objects.requireNonNull(oldestWaitingAcceptedAt, "oldestWaitingAcceptedAt");
        Objects.requireNonNull(readAt, "readAt");
    }
}
