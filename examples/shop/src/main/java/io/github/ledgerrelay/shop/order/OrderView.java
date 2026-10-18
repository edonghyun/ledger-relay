package io.github.ledgerrelay.shop.order;

import java.time.Instant;

/** 화면과 API가 보는 주문. {@code halted}는 주문의 명령 중 하나가 멈췄다는 표시다. */
public record OrderView(long id, long buyerId, String product, long amountSatang, String state, String chip, String label,
        String failureReason, boolean halted, Instant createdAt) {

    public static OrderView of(ShopOrder order, boolean halted) {
        return new OrderView(order.id(), order.buyerId(), order.productCode(), order.amountSatang(), order.state().name(),
                order.state().chip(), order.state().label(), order.failureReason(), halted, order.createdAt());
    }
}
