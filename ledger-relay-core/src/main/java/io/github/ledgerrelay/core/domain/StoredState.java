package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.ResultCategory;

/** 저장 상태(스펙 §4.1, D7). 앱이 보는 조회 상태(§4.2)는 공개 CommandStatus.State다. "미확정"은 상태가 아니라 submit_count로 파생한다. */
public enum StoredState {
    PENDING,
    CLAIMED,
    APPLIED,
    REJECTED,
    CONFLICT,
    SKIPPED;

    /** 최종 상태는 다시 바뀌지 않는다(G5). */
    public boolean isFinal() {
        return this != PENDING && this != CLAIMED;
    }

    /** TB 결과 분류(§4.4)를 받은 처리 중 명령이 끝나는 상태. */
    public static StoredState finalStateFor(ResultCategory category) {
        return switch (category) {
            case APPLIED -> APPLIED;
            case CONFLICT, UNSUPPORTED -> CONFLICT;
            case BUSINESS, PRIOR_FAILURE, INVALID -> REJECTED;
        };
    }
}
