package io.github.ledgerrelay.core.domain;

import java.util.Set;

/** 전이표 한 칸의 결과(스펙 §4.3). */
public record Transition(Outcome outcome, StoredState to, Set<Effect> effects) {

    public enum Outcome {
        /** {@link #to()} 상태로 이동하고 {@link #effects()}를 낸다. */
        MOVE,
        /** 게이트가 닫혀 아무것도 하지 않는다. */
        UNCHANGED,
        /** fenced UPDATE가 0행을 갱신한다. 정상적인 경쟁 결과이며 stale_write_rejected로 기록한다. */
        ZERO_ROWS,
        /** 모듈이 이 사건을 만들면 버그다. */
        IMPOSSIBLE,
        /** 이 상태에서는 개념상 생길 수 없는 사건이다. */
        NOT_APPLICABLE
    }

    public enum Effect {
        /** lease_token += 1, lease_expires_at 갱신(§5.2). */
        INCREMENT_LEASE_TOKEN,
        /** 결과 반영 핸들러를 같은 트랜잭션에서 실행한다(G6). */
        RUN_HANDLER,
        /** next_attempt_at = now() + backoff. */
        BACKOFF,
        /** TB 결과를 known_result로 남겨 다음 claim이 TB를 건너뛰게 한다(§5.4). */
        KEEP_KNOWN_RESULT
    }

    public Transition {
        effects = Set.copyOf(effects);
    }

    public static Transition moveTo(StoredState to, Effect... effects) {
        return new Transition(Outcome.MOVE, to, Set.of(effects));
    }

    public static Transition unchanged() {
        return new Transition(Outcome.UNCHANGED, null, Set.of());
    }

    public static Transition zeroRows() {
        return new Transition(Outcome.ZERO_ROWS, null, Set.of());
    }

    public static Transition impossible() {
        return new Transition(Outcome.IMPOSSIBLE, null, Set.of());
    }

    public static Transition notApplicable() {
        return new Transition(Outcome.NOT_APPLICABLE, null, Set.of());
    }
}
