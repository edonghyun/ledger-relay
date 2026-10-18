package io.github.ledgerrelay.shop.order;

/** No such order. HTTP 404. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long orderId) {
        super("Order " + orderId + " does not exist");
    }
}
