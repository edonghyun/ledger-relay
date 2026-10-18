package io.github.ledgerrelay.shop.order;

/**
 * A payment with different content arrived under the same idempotency key. Silently returning the existing order would
 * make the caller believe it bought a different product, so the request is rejected. HTTP 422.
 */
public class IdempotencyMismatchException extends RuntimeException {

    public IdempotencyMismatchException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was already used for a payment by another buyer or for another product");
    }
}
