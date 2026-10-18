package io.github.ledgerrelay.shop.order;

/** 같은 멱등 키로 내용이 다른 결제가 왔다. 같은 주문을 조용히 돌려주면 다른 상품을 샀다고 믿게 되므로 거부한다. HTTP 422. */
public class IdempotencyMismatchException extends RuntimeException {

    public IdempotencyMismatchException(String idempotencyKey) {
        super("멱등 키 " + idempotencyKey + "는 다른 구매자나 상품의 결제에 이미 쓰였습니다");
    }
}
