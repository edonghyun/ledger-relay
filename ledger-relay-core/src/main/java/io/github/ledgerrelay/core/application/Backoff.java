package io.github.ledgerrelay.core.application;

import java.time.Duration;
import java.util.Objects;

/** 다시 시도할 때까지의 대기(worker.backoff). 실패할 때마다 두 배로 늘고 max에서 멈춘다. */
public record Backoff(Duration initial, Duration max) {

    public Backoff {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("backoff initial은 0보다 길어야 합니다: " + initial);
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("backoff max(" + max + ")는 initial(" + initial + ")보다 짧을 수 없습니다");
        }
    }

    /** @param failures 지금까지의 실패 횟수. 0과 1이면 initial이다 */
    public Duration after(long failures) {
        Duration wait = initial;
        // 상한에 닿으면 멈추므로 failures가 커도 넘치지 않는다
        for (long n = 1; n < failures && wait.compareTo(max) < 0; n++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
