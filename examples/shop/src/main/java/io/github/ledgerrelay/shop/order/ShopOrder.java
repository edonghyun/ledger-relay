package io.github.ledgerrelay.shop.order;

import java.time.Instant;

/** One order. Amounts are in satang (1/100 baht). {@code failureReason} is the TB result code of a failed order. */
public record ShopOrder(long id, String idempotencyKey, long buyerId, String productCode, long amountSatang,
        OrderState state, String failureReason, Instant createdAt) {

    /** Ordering key of this order. The reserve, post and void of one order go out on this key in seq order. */
    public String orderingKey() {
        return orderingKey(id);
    }

    public static String orderingKey(long orderId) {
        return "order-" + orderId;
    }

    public static String commandId(long orderId, String step) {
        return orderingKey(orderId) + "-" + step;
    }
}
