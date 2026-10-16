package io.github.ledgerrelay.core;

/** 저장 상태(스펙 §4.1, D7). "미확정"은 상태가 아니라 submit_count로 파생한다. */
public enum CommandState {
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
}
