package io.github.ledgerrelay.core.domain;

/** One command of the same ordering key, as the gate sees it. */
public record QueuedCommand(String commandId, long seq, StoredState state) {
}
