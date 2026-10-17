package io.github.ledgerrelay.core.domain;

import java.util.Set;

/** 상태 기계가 한 일. */
public sealed interface StepResult {

    /** 행이 {@link #row()}로 바뀌었다. 서비스는 {@link #effects()} 중 BACKOFF와 RUN_HANDLER를 맡는다. */
    record Moved(CommandRow row, Set<Transition.Effect> effects) implements StepResult {
        public Moved {
            effects = Set.copyOf(effects);
        }
    }

    /** 게이트가 닫혀 아무 일도 없었다. */
    record Unchanged() implements StepResult {
    }

    /** 오래된 토큰이거나 처리 중이 아닌 행이라 쓰기가 0행이었다. 정상적인 경쟁 결과다(stale_write_rejected). */
    record Fenced() implements StepResult {
    }
}
