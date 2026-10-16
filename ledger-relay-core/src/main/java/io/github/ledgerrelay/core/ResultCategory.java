package io.github.ledgerrelay.core;

/** TB 결과 코드의 분류(스펙 §4.4). 분류가 명령의 최종 상태와 알림 여부를 정한다. */
public enum ResultCategory {
    /** created, exists */
    APPLIED(CommandState.APPLIED, false),
    /** exists_with_different_*: 같은 ID에 다른 내용. 버그 신호다(G7). */
    CONFLICT(CommandState.CONFLICT, true),
    /** 업무 거절: 잔액, 한도, 만료, 이미 확정·해제됨 등. */
    BUSINESS(CommandState.REJECTED, false),
    /** id_already_failed: 이전 시도가 실패했다. 원래 사유는 알 수 없다. */
    PRIOR_FAILURE(CommandState.REJECTED, false),
    /** 잘못된 요청: 앱 버그 또는 설정 오류. */
    INVALID(CommandState.REJECTED, true),
    /** v1이 쓰지 않는 기능(linked, imported)의 코드. CONFLICT로 다룬다(스펙 §4.4, P1-5). */
    UNSUPPORTED(CommandState.CONFLICT, true);

    private final CommandState finalState;
    private final boolean alerts;

    ResultCategory(CommandState finalState, boolean alerts) {
        this.finalState = finalState;
        this.alerts = alerts;
    }

    public CommandState finalState() {
        return finalState;
    }

    public boolean alerts() {
        return alerts;
    }
}
