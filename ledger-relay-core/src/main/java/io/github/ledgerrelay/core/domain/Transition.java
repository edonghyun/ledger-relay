package io.github.ledgerrelay.core.domain;

import java.util.Set;

/** The result of one cell of the transition table. */
public record Transition(Outcome outcome, StoredState to, Set<Effect> effects) {

    public enum Outcome {
        /** Moves to state {@link #to()} and produces {@link #effects()}. */
        MOVE,
        /** The gate is closed; nothing is done. */
        UNCHANGED,
        /** The fenced UPDATE updates zero rows. A normal race outcome, recorded as stale_write_rejected. */
        ZERO_ROWS,
        /** If the module produces this event, it is a bug. */
        IMPOSSIBLE,
        /** The event cannot conceptually occur in this state. */
        NOT_APPLICABLE
    }

    public enum Effect {
        /** lease_token += 1, and lease_expires_at is renewed. */
        INCREMENT_LEASE_TOKEN,
        /** Runs the outcome handler in the same transaction (G6). */
        RUN_HANDLER,
        /** next_attempt_at = now() + backoff. */
        BACKOFF,
        /** Keeps the TB result as known_result so the next claim bypasses TB. */
        KEEP_KNOWN_RESULT,
        /** submit_count += 1, and first_submitted_at = now() the first time. */
        RECORD_SUBMISSION,
        /** handler_failures += 1. This is what exposes blocked keys in the metrics. */
        COUNT_HANDLER_FAILURE
    }

    /** Only a move has a target state and effects. The other outcomes have neither. */
    public Transition {
        effects = Set.copyOf(effects);
        if (outcome == Outcome.MOVE && to == null) {
            throw new IllegalArgumentException("A move (MOVE) requires a target state");
        }
        if (outcome != Outcome.MOVE && (to != null || !effects.isEmpty())) {
            throw new IllegalArgumentException(outcome + " must have no target state and no effects: " + to + ", " + effects);
        }
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
