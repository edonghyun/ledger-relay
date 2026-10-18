package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.HaltReason;
import java.time.Instant;
import java.util.Objects;

/** 멈춘 행의 기록: 왜, 언제 멈췄는가. 멈춘(HALTED) 행에만 있다. */
public record HaltMark(HaltReason reason, Instant haltedAt) {

    public HaltMark {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(haltedAt, "haltedAt");
    }
}
