package io.github.ledgerrelay.shop.order;

/**
 * 주문 상태(03b §7.2). 카드 결제의 승인 → 매입과 같다. 결제는 예약(승인)만 하고, 돈이 가맹점으로 가는 확정(매입)은 출고 때 한다.
 *
 * <pre>
 * 접수됨 → 결제 승인됨 → 출고 요청 → 결제 완료
 * 접수됨/결제 승인됨 → 취소 요청 → 취소됨
 * 실패(예약 거절 등, 사유와 함께)
 * </pre>
 */
public enum OrderState {
    ACCEPTED("accepted", "접수됨"),
    AUTHORIZED("authorized", "결제 승인됨"),
    SHIP_REQUESTED("shipping", "출고 요청"),
    PAID("paid", "결제 완료"),
    CANCEL_REQUESTED("cancelling", "취소 요청"),
    CANCELLED("cancelled", "취소됨"),
    FAILED("failed", "실패");

    private final String chip;
    private final String label;

    OrderState(String chip, String label) {
        this.chip = chip;
        this.label = label;
    }

    /** 화면 칩에 쓰는 짧은 영어 이름. */
    public String chip() {
        return chip;
    }

    /** 주문 상세에 쓰는 한국어 이름. */
    public String label() {
        return label;
    }

    /** 더 바뀌지 않는 상태. */
    public boolean isFinal() {
        return this == PAID || this == CANCELLED || this == FAILED;
    }
}
