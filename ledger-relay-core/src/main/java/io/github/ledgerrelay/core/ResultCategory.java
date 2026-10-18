package io.github.ledgerrelay.core;

/** TB 결과 코드의 분류. 분류가 알림 여부를 정한다. 분류가 보내는 최종 저장 상태는 domain의 StoredState.finalStateFor가 정한다. */
public enum ResultCategory {
    /** created, exists */
    APPLIED(false),
    /** exists_with_different_*: 같은 ID에 다른 내용. 버그 신호다(G7). */
    CONFLICT(true),
    /** 업무 거절: 잔액, 한도, 만료, 이미 확정·해제됨 등. */
    BUSINESS(false),
    /**
     * id_already_failed: 응답이 유실된 이전 시도가 TB의 일시적 실패 7종(잔액·한도 2, 계좌 닫힘 2, 계좌·예약 없음 3) 중
     * 하나로 끝났다. 어느 것인지는 알 수 없고 잘못된 요청(INVALID)일 수도 있으므로 알린다.
     */
    PRIOR_FAILURE(true),
    /** 잘못된 요청: 앱 버그 또는 설정 오류. */
    INVALID(true),
    /** v1이 쓰지 않는 기능(linked, imported)의 코드. CONFLICT로 다룬다. */
    UNSUPPORTED(true);

    private final boolean alerts;

    ResultCategory(boolean alerts) {
        this.alerts = alerts;
    }

    public boolean alerts() {
        return alerts;
    }
}
