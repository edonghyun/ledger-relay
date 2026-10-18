package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.HaltReason;
import io.github.ledgerrelay.core.LedgerCommand;
import java.util.List;
import java.util.Optional;

/**
 * The ordering gate (G4, docs/design.md). A command is submitted only when every command with a smaller seq under the
 * same ordering key is in a final state. A post or void is skipped if its referenced reservation ended in a final
 * state other than APPLIED.
 *
 * <p>The application services run this rule on claim. The service passes only the one referenced reservation as
 * {@code sameKey}. The store contract ({@code CommandStoreContract}) guarantees that only the head of each key is a
 * candidate, so the check for an earlier unfinished command ({@link GateDecision#CLOSED}) is for when that contract
 * breaks.
 */
public final class OrderingGate {

    private OrderingGate() {
    }

    /**
     * @param candidate the command to submit
     * @param candidateSeq that command's seq within its key
     * @param sameKey commands of the same ordering key (the service passes only the referenced reservation). Order
     *                does not matter. The candidate itself may be included, and commands deleted by the retention
     *                policy may be missing.
     */
    public static GateDecision decide(LedgerCommand candidate, long candidateSeq, List<QueuedCommand> sameKey) {
        return decide(candidate.kind(), candidate.reservation(), candidateSeq, sameKey);
    }

    /**
     * @param kind                 the kind of the command to submit
     * @param reservationCommandId the reservation a post or void refers to. Empty otherwise
     * @param candidateSeq         that command's seq within its key
     * @param sameKey              commands of the same ordering key (the service passes only the referenced
     *                             reservation). Order does not matter. The candidate itself may be included, and
     *                             commands deleted by the retention policy may be missing.
     */
    public static GateDecision decide(CommandKind kind, Optional<String> reservationCommandId, long candidateSeq,
            List<QueuedCommand> sameKey) {
        boolean earlierUnfinished = sameKey.stream()
                .anyMatch(q -> q.seq() < candidateSeq && !q.state().isFinal());
        if (earlierUnfinished) {
            return GateDecision.CLOSED;
        }
        if (!kind.referencesReservation()) {
            return GateDecision.OPEN;
        }
        String reservationId = reservationCommandId.orElseThrow();
        Optional<QueuedCommand> reservation = sameKey.stream()
                .filter(q -> q.commandId().equals(reservationId))
                .findFirst();
        if (reservation.isEmpty()) {
            // The reference check passed at acceptance, and the retention policy does not delete a reservation that an
            // unfinished post or void refers to. If it is missing anyway, it is a bug or a direct DB edit. If the
            // reservation ended as a conflict (CONFLICT), another command's transfer sits under the same TB id, and
            // sending could post that transfer. If the store failed to read a locked reservation, the post would go
            // before the reservation. Either way, halt without sending to TB (docs/failure-modes.md F10).
            return GateDecision.HALT;
        }
        StoredState state = reservation.get().state();
        if (state == StoredState.APPLIED) {
            return GateDecision.OPEN;
        }
        if (state.isFinal()) {
            return GateDecision.SKIP;
        }
        // If the reservation is unfinished but earlierUnfinished above did not catch it, the reservation's seq is
        // greater
        // than or equal to the candidate's. Acceptance checks should have blocked this order. Waiting quietly would
        // stall the key forever, so halt and report.
        return GateDecision.HALT;
    }

    /**
     * For the same input on which {@link #decide} returned {@link GateDecision#HALT}, answers why it halted. If the
     * referenced reservation is not among the commands of the same key, the reservation is missing; if it is, the
     * reservation does not have an earlier seq.
     */
    public static HaltReason explainHalt(CommandKind kind, Optional<String> reservationCommandId, long candidateSeq,
            List<QueuedCommand> sameKey) {
        if (!kind.referencesReservation()) {
            throw new IllegalArgumentException("Bug: " + kind + " does not refer to a reservation, so the gate never halts it");
        }
        String reservationId = reservationCommandId.orElseThrow();
        boolean present = sameKey.stream().anyMatch(q -> q.commandId().equals(reservationId));
        return present ? HaltReason.RESERVATION_NOT_EARLIER : HaltReason.RESERVATION_MISSING;
    }
}
