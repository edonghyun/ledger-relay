package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;

/** 주문이 접수한 원장 명령 하나. */
public record OrderCommand(long orderId, String commandId, CommandKind kind) {
}
