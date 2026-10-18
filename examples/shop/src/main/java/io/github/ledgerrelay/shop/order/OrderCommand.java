package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;

/** One ledger command accepted for an order. */
public record OrderCommand(long orderId, String commandId, CommandKind kind) {
}
