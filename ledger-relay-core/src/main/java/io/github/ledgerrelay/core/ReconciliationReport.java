package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * The result of one reconciliation page (operator port).
 *
 * @param checked      the number of commands checked. Commands that ended as conflict (CONFLICT) are not counted
 * @param matched      how many of them match between PG and TB
 * @param mismatches   the mismatched commands. At most {@link #MAX_MISMATCHES}; {@code checked - matched} gives the
 *                     full count
 * @param lastPosition the position of the last row seen on this page. Pass it to read the next page
 */
public record ReconciliationReport(long checked, long matched, List<Mismatch> mismatches, long lastPosition) {

    public static final int MAX_MISMATCHES = 100;

    public ReconciliationReport {
        mismatches = List.copyOf(mismatches);
    }

    /** One mismatched command. */
    public record Mismatch(String commandId, BigInteger transferId, Kind kind) {

        public Mismatch {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(transferId, "transferId");
            Objects.requireNonNull(kind, "kind");
        }

        /** How it mismatched. */
        public enum Kind {
            /** PG recorded it as applied in TB, but TB has no such transfer. */
            MISSING_IN_LEDGER,
            /** PG recorded it as rejected or skipped, but TB has the transfer. */
            UNEXPECTED_IN_LEDGER,
            /** TB has it, but the kind, accounts, amount or another field differ from the command in PG. */
            DIFFERENT
        }
    }
}
