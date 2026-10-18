package io.github.ledgerrelay.core;

/** 명령이 멈춘(HALTED) 이유(스펙 §5.2 HALT). 운영자가 무엇을 고칠지 고른다. */
public enum HaltReason {
    /** post·void가 참조한 예약이 같은 키 명령들 속에 없다. 버그나 DB 직접 수정이다(F10). */
    RESERVATION_MISSING,
    /** 참조한 예약이 확정·해제보다 앞 순번이 아니다. 접수 검사가 막았어야 하는 순서다(F9). */
    RESERVATION_NOT_EARLIER,
    /** 행의 데이터가 규칙과 어긋나 판단할 수 없었다(F29). */
    MALFORMED_ROW
}
