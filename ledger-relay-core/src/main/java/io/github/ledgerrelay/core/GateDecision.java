package io.github.ledgerrelay.core;

/** 순서 게이트의 결정(스펙 §5.2). */
public enum GateDecision {
    /** 제출한다. */
    OPEN,
    /** 같은 키의 앞 명령을 기다린다. */
    CLOSED,
    /** post·void가 참조한 예약이 적용되지 않고 끝났다. claim한 뒤 TB에 보내지 않고 SKIPPED로 끝낸다. */
    SKIP,
    /** 정상 경로에서 나올 수 없는 입력이다. 제출하지 않고 멈추며 알린다. */
    HALT;

    /** 전이표(§4.3)의 claim 사건으로 바꾼다. */
    public CommandEvent claimEvent() {
        return switch (this) {
            // 건너뛸 명령도 먼저 claim한다. 건너뜀(RESERVATION_NOT_APPLIED)은 claim 뒤의 사건이다.
            case OPEN, SKIP -> CommandEvent.CLAIM_WITH_GATE_OPEN;
            case CLOSED, HALT -> CommandEvent.CLAIM_WITH_GATE_CLOSED;
        };
    }

    /** 사람이 봐야 하는 결정인가. */
    public boolean alerts() {
        return this == HALT;
    }
}
