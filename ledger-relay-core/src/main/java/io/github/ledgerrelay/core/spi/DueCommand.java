package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.domain.QueuedCommand;
import java.util.Objects;
import java.util.Optional;

/**
 * One claim candidate, and what the gate looks at for it.
 *
 * @param reservation the seq and state of the reservation the post or void refers to. Empty if there is no reference
 *                    or no reservation row
 */
public record DueCommand(CommandRecord command, Optional<QueuedCommand> reservation) {

    public DueCommand {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reservation, "reservation");
    }
}
