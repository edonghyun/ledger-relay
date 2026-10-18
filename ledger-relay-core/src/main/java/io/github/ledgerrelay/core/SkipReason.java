package io.github.ledgerrelay.core;

/** Why a command ended as skipped (SKIPPED) without going to TB (docs/failure-modes.md F9). */
public enum SkipReason {
    /** The reservation that the post or void refers to ended without being applied. */
    RESERVATION_NOT_APPLIED,
    /** An operator abandoned the halted (HALTED) command. It never went to TB. */
    ABANDONED_BY_OPERATOR
}
