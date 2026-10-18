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
 */
public record Backlog(long unfinished, long halted, Optional<Instant> oldestUnfinishedAcceptedAt) {

    public Backlog {
        Objects.requireNonNull(oldestUnfinishedAcceptedAt, "oldestUnfinishedAcceptedAt");
    }
}
