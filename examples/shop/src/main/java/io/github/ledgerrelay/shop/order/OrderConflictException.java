package io.github.ledgerrelay.shop.order;

/**
 * The request is not allowed in the current state of the order (shipping before authorization, cancelling after
 * shipping, etc.). HTTP 409.
 */
public class OrderConflictException extends RuntimeException {

    public OrderConflictException(String reason) {
        super(reason);
    }
}
