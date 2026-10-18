package io.github.ledgerrelay.core;

import java.util.List;

/**
 * The operator port (O5). The app's operations screen or admin API calls it for work that a person must look at and
 * decide. Call every method outside a transaction.
 */
public interface LedgerRelayOperator {

    /**
     * Returns halted (HALTED) commands in the order they halted (row order). To read the next page, pass the last
     * item's {@link HaltedCommand#position()} as {@code afterPosition}. The first page is 0.
     *
     * @param limit between 1 and 1000
     * @throws IllegalArgumentException limit is out of range
     */
    List<HaltedCommand> haltedCommands(long afterPosition, int limit);

    /**
     * Abandons a halted command (F9, F27). The command ends as skipped without being sent to TB, and in the same
     * transaction the handler receives the reason "abandoned by operator". If the handler fails, the command stays
     * halted and the result is {@link AbandonOutcome#HANDLER_FAILED}.
     *
     * @throws CommandNotFoundException the command was never accepted
     * @throws CannotAbandonException   the command is not halted ({@code NOT_HALTED}), or it was already sent to TB
     *                                  ({@code ALREADY_SUBMITTED})
     */
    AbandonOutcome abandonHalted(String commandId);

    /**
     * Reads one page of commands that reached a final state, in row order, and matches them against the transfers that
     * actually exist in TB. It only reads and fixes nothing. To read the next page, pass
     * {@link ReconciliationReport#lastPosition()}. The first page is 0.
     *
     * @param limit between 1 and 1000
     * @throws IllegalArgumentException limit is out of range
     * @throws io.github.ledgerrelay.core.spi.LedgerUnavailableException TB did not answer the lookup within the set
     *                                                                   time
     */
    ReconciliationReport reconcile(long afterPosition, int limit);
}
