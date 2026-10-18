package io.github.ledgerrelay.shop.order;

/** 주문의 지금 상태에서는 할 수 없는 요청이다(승인 전 출고, 출고 뒤 취소 등). HTTP 409. */
public class OrderConflictException extends RuntimeException {

    public OrderConflictException(String reason) {
        super(reason);
    }
}
