package io.github.ledgerrelay.core.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 지금 쌓인 일(health).
 *
 * @param unfinished                 접수됐거나 처리 중인 명령 수. 멈춘 명령은 빼고 센다
 * @param halted                     멈춘 명령 수. 운영자가 풀어야 한다
 * @param oldestUnfinishedAcceptedAt 끝나지 않은 명령 중 가장 먼저 접수된 시각
 * @param countsCapped               저장소가 정한 상한까지만 셌다. 실제 수는 그 이상일 수 있다
 */
public record Backlog(long unfinished, long halted, Optional<Instant> oldestUnfinishedAcceptedAt,
        boolean countsCapped) {

    public Backlog {
        Objects.requireNonNull(oldestUnfinishedAcceptedAt, "oldestUnfinishedAcceptedAt");
    }
}
