package io.github.ledgerrelay.shop.order;

/** 결제 요청의 결과. {@code created}가 false면 같은 멱등 키로 이미 만든 주문이다. */
public record Checkout(ShopOrder order, boolean created) {
}
