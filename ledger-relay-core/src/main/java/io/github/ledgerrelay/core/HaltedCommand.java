package io.github.ledgerrelay.core;

import java.time.Instant;
import java.util.Objects;

/**
 * 멈춘 명령 하나(운영자 포트).
 *
 * @param position 저장된 행의 순서. 다음 페이지를 읽을 때 넘긴다
 */
public record HaltedCommand(long position, String commandId, String orderingKey, long seq, CommandKind kind,
        HaltReason reason, Instant haltedAt) {

    public HaltedCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(orderingKey, "orderingKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(haltedAt, "haltedAt");
    }
}
