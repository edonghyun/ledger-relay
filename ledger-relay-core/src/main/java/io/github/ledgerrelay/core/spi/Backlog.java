package io.github.ledgerrelay.core.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The work queued right now (health).
 *
 * @param unfinished                 the number of accepted or in-progress commands. Halted commands are not counted
 * @param halted                     the number of halted commands. An operator must resolve them
 * @param oldestUnfinishedAcceptedAt the acceptance time of the oldest unfinished command
 * @param countsCapped               counting stopped at a cap the store sets. The real numbers may be higher
 */
public record Backlog(long unfinished, long halted, Optional<Instant> oldestUnfinishedAcceptedAt,
        boolean countsCapped) {

    public Backlog {
        Objects.requireNonNull(oldestUnfinishedAcceptedAt, "oldestUnfinishedAcceptedAt");
    }
}
