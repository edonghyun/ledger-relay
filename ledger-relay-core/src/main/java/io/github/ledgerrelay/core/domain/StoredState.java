package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.ResultCategory;

/** 저장 상태. 앱이 보는 조회 상태는 공개 CommandStatus.State다. "미확정"은 상태가 아니라 submit_count로 파생한다. */
public enum StoredState {
    PENDING,
    CLAIMED,
    APPLIED,
    REJECTED,
    CONFLICT,
    SKIPPED,
    /** 정상 경로에서 나올 수 없는 입력이라 멈췄다. 최종 상태가 아니므로 그 키는 막힌다. 운영자만 풀 수 있다. */
    HALTED;

    /** 최종 상태는 다시 바뀌지 않는다(G5). */
    public boolean isFinal() {
        return this == APPLIED || this == REJECTED || this == CONFLICT || this == SKIPPED;
    }

    /** TB 결과 분류를 받은 처리 중 명령이 끝나는 상태. */
    public static StoredState finalStateFor(ResultCategory category) {
        return switch (category) {
            case APPLIED -> APPLIED;
            case CONFLICT, UNSUPPORTED -> CONFLICT;
            case BUSINESS, PRIOR_FAILURE, INVALID -> REJECTED;
        };
    }

    /** 조회 상태. 접수·처리 중은 TB에 보낸 적이 있는지로 나뉜다. 응답이 유실된 미확정도 처리 중이다. */
    public CommandStatus.State queryState(long submitCount) {
        if (submitCount < 0) {
            throw new IllegalArgumentException("submitCount는 0 이상이어야 합니다: " + submitCount);
        }
        return switch (this) {
            case PENDING, CLAIMED -> submitCount == 0 ? CommandStatus.State.ACCEPTED : CommandStatus.State.IN_PROGRESS;
            case APPLIED -> CommandStatus.State.APPLIED;
            case REJECTED -> CommandStatus.State.REJECTED;
            case CONFLICT -> CommandStatus.State.CONFLICT;
            case SKIPPED -> CommandStatus.State.SKIPPED;
            case HALTED -> CommandStatus.State.HALTED;
        };
    }
}
