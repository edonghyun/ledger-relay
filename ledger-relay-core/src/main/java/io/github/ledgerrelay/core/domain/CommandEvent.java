package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.ResultCategory;

/** 전이표(스펙 §4.3)의 사건. claim은 게이트 결정(§5.2)에 따라 열림·닫힘으로 나뉘고, 건너뜀은 claim 뒤의 사건이다. */
public enum CommandEvent {
    /** claim: 게이트가 열렸다. */
    CLAIM_WITH_GATE_OPEN,
    /** claim: 같은 키의 앞 명령이 미완료라 게이트가 닫혔다. */
    CLAIM_WITH_GATE_CLOSED,
    /** claim: 정상 경로에서 나올 수 없는 입력이라 게이트가 멈췄다(HALT). */
    CLAIM_WITH_GATE_HALTED,
    /**
     * TB에 보내기 직전에 제출을 기록한다(submit_count += 1, 처음이면 first_submitted_at). 보내기 전에 남기므로
     * 응답이 유실돼도 "보낸 적이 있다"가 남는다(§4.2 처리 중). known_result가 있으면 보내지 않으므로 이 사건도 없다.
     */
    SUBMITTING,
    /** claim한 post·void의 참조 예약이 APPLIED가 아닌 최종 상태로 끝났다. TB에 보내지 않고 건너뛴다. */
    RESERVATION_NOT_APPLIED,
    /** TB 성공(created, exists). */
    TB_APPLIED,
    /** TB 확정 실패(업무 거절, 이전 실패, 잘못된 요청). */
    TB_REJECTED,
    /** TB 내용 충돌(exists_with_different_*)과 v1 해당 없음 코드. */
    TB_CONFLICT,
    /** submit-timeout 안에 응답이 없거나 예외가 났다. */
    NO_REPLY_OR_ERROR,
    /** 결과 반영 핸들러가 예외를 던졌다(§5.4). */
    HANDLER_FAILED,
    /** lease가 만료된 명령을 다른 워커가 다시 claim한다. */
    LEASE_EXPIRED,
    /** 이미 넘어간 lease 토큰으로 쓰려 한다(늦은 워커). */
    STALE_TOKEN_WRITE,
    /** 운영자가 멈춘 명령을 포기한다. TB에 보내지 않고 건너뜀으로 끝낸다. */
    ABANDONED_BY_OPERATOR;

    /** TB 결과 분류(§4.4)를 전이표의 TB 사건으로 바꾼다. */
    public static CommandEvent of(ResultCategory category) {
        return switch (category) {
            case APPLIED -> TB_APPLIED;
            case CONFLICT, UNSUPPORTED -> TB_CONFLICT;
            case BUSINESS, PRIOR_FAILURE, INVALID -> TB_REJECTED;
        };
    }
}
