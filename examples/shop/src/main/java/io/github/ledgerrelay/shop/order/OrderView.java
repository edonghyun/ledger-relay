package io.github.ledgerrelay.shop.order;

import java.time.Instant;

/** The order as the UI and the API see it. {@code halted} marks that one of the order's commands is halted. */
public record OrderView(long id, long buyerId, String product, long amountSatang, String state, String chip, String label,
        String failureReason, boolean halted, Instant createdAt) {

    public static OrderView of(ShopOrder order, boolean halted) {
        return new OrderView(order.id(), order.buyerId(), order.productCode(), order.amountSatang(), order.state().name(),
                order.state().chip(), order.state().label(), order.failureReason(), halted, order.createdAt());
    }
}
