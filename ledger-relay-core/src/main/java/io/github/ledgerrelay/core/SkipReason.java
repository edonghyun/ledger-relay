package io.github.ledgerrelay.core;

/** 명령이 TB에 가지 않고 건너뜀(SKIPPED)으로 끝난 이유(docs/failure-modes.md F9). */
public enum SkipReason {
    /** 확정·해제가 참조한 예약이 적용되지 않고 끝났다. */
    RESERVATION_NOT_APPLIED,
    /** 멈춘(HALTED) 명령을 운영자가 포기했다. TB에는 한 번도 가지 않았다. */
    ABANDONED_BY_OPERATOR
}
