package io.github.ledgerrelay.core.spi;

import java.util.List;
import java.util.Optional;

/**
 * The command store. It only stores and reads. The services (core.application) decide what to write using the domain
 * rules. Every method is called inside a transaction opened by {@link Transactions}. {@code CommandStoreContract} in
 * testFixtures defines the contract. It touches only command rows and never locks ordering key rows; key rows are
 * locked by {@link OrderingKeys}.
 */
public interface CommandStore {

    /**
     * Inserts. If a row with the same commandId exists, it does not insert and returns that row. Empty if it inserted.
     */
    Optional<CommandRecord> insertIfAbsent(NewCommand command);

    Optional<CommandRecord> find(String commandId);

    /**
     * Locks and returns up to limit claim candidates, in the order they became sendable. A candidate is the unfinished
     * head command of an ordering key (a head held by a finished command is advanced first by
     * {@link OrderingKeys#passOnFinishedHeads}): an accepted one whose next attempt time has passed, or an in-progress
     * one whose lease has expired. The earliest of those times (next attempt time when accepted, lease expiry when in
     * progress) goes first; ties go in acceptance order. Rows locked by another transaction are passed over. Returning
     * only head commands keeps the later commands of a blocked key from taking up the limit.
     */
    List<DueCommand> lockDue(int limit);

    /** Counts the work queued right now (health). */
    Backlog backlog();

    /**
     * Returns up to limit commands in a final state, in row order, starting after {@code afterRowId} (reconciliation).
     */
    List<CommandRecord> findFinished(long afterRowId, int limit);

    /**
     * Returns up to limit halted (HALTED) commands, in row order, starting after {@code afterRowId} (operator port).
     */
    List<CommandRecord> findHalted(long afterRowId, int limit);

    /**
     * If the row exists and its state and token match the expected ones, changes it and returns true. Otherwise changes
     * nothing and returns false.
     */
    boolean compareAndSet(RowChange change);
}
