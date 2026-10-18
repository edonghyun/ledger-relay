package io.github.ledgerrelay.shop.order;

/** 그런 주문이 없다. HTTP 404. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long orderId) {
        super("주문 " + orderId + "이 없습니다");
    }
}
