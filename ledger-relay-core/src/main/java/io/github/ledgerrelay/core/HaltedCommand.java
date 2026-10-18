package io.github.ledgerrelay.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One halted command (operator port).
 *
 * @param position the order of the stored row. Pass it to read the next page
 */
public record HaltedCommand(long position, String commandId, String orderingKey, long seq, CommandKind kind,
        HaltReason reason, Instant haltedAt) {

    public HaltedCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(orderingKey, "orderingKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(haltedAt, "haltedAt");
    }
}
