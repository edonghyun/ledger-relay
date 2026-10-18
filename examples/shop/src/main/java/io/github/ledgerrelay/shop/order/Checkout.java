package io.github.ledgerrelay.shop.order;

/** Result of a payment request. {@code created} is false when an order already existed for the idempotency key. */
public record Checkout(ShopOrder order, boolean created) {
}
