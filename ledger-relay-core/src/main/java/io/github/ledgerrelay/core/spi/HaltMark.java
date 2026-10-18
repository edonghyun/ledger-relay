package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.HaltReason;
import java.time.Instant;
import java.util.Objects;

/** The record of a halted row: why and when it halted. Present only on halted (HALTED) rows. */
public record HaltMark(HaltReason reason, Instant haltedAt) {

    public HaltMark {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(haltedAt, "haltedAt");
    }
}
