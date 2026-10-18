package io.github.ledgerrelay.shop.order;

import java.time.Instant;

/** 주문 한 건. 금액은 사탕(1/100 바트) 단위다. {@code failureReason}은 실패일 때 TB 결과 코드 이름이다. */
public record ShopOrder(long id, String idempotencyKey, long buyerId, String productCode, long amountSatang,
        OrderState state, String failureReason, Instant createdAt) {

    /** 이 주문의 순서 키. 한 주문의 예약·확정·해제는 이 키에서 순번대로 나간다. */
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
