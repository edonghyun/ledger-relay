package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.CommandConflictException;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandReceipt;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.HaltReason;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.ReservationNotFoundException;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.CommandStateMachine;
import io.github.ledgerrelay.core.domain.ContentHash;
import io.github.ledgerrelay.core.domain.GateDecision;
import io.github.ledgerrelay.core.domain.OrderingGate;
import io.github.ledgerrelay.core.domain.Step;
import io.github.ledgerrelay.core.domain.StepResult;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The service that owns the life of a command. All business decisions happen here and in domain. The store only
 * stores.
 *
 * <p>Rows each transaction locks (held until commit):
 * <ul>
 *   <li>{@link #submit} (app transaction): the ordering key row, then the new command row. The app locks its order rows
 *       before or after that.
 *   <li>{@link #claim}: key rows whose head is a finished command (passed over if another transaction holds them),
 *       then candidate command rows (passed over if locked). The handler does not run.
 *   <li>Worker writes ({@link WorkerWrites}): the command row, then the order rows the handler locks. They do not lock
 *       the ordering key row.
 * </ul>
 * The app's acceptance is the only transaction that locks both ordering key rows and order rows, so no other
 * transaction locks them in the opposite order and can deadlock with it.
 */
public final class CommandLifecycle implements LedgerRelay {

    private final OrderingKeys keys;
    private final CommandStore store;
    private final Transactions transactions;
    private final TransferIdGenerator transferIds;
    private final WorkerWrites workerWrites;

    public CommandLifecycle(OrderingKeys keys, CommandStore store, Transactions transactions,
            TransferIdGenerator transferIds, LedgerOutcomeHandler handler, Backoff backoff) {
        this.keys = keys;
        this.store = store;
        this.transactions = transactions;
        this.transferIds = transferIds;
        this.workerWrites = new WorkerWrites(store, transactions, handler, backoff);
    }

    @Override
    public CommandReceipt submit(LedgerCommand command) {
        transactions.requireActive(); // G1: bound to the app transaction
        // Lock the key and take a seq. Concurrent acceptances on the same key queue here, so seq order equals commit
        // order
        long seq = keys.lockKeyAndTakeSeq(command.orderingKey());
        // If the same commandId exists, compare the content. This runs before the reference check: after the retention
        // job deletes the reservation, resending the same post must still return the first receipt (F28)
        String hash = ContentHash.of(command);
        Optional<CommandRecord> existing = store.find(command.commandId());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), hash);
        }
        // Reference check. The reservation's transfer id never changes, so it is fixed now
        Optional<BigInteger> pendingTransferId = command.reservation()
                .map(reservationId -> reservationTransferId(command, reservationId));
        // Issue a transfer id and insert. If another transaction inserted the same commandId meanwhile, the store
        // returns that row
        BigInteger transferId = transferIds.next();
        NewCommand row = new NewCommand(command.commandId(), command.orderingKey(), seq, command.reservation(),
                transferId, hash, TransferBody.of(command, pendingTransferId));
        return store.insertIfAbsent(row)
                .map(raced -> sameOrConflict(raced, hash))
                .orElseGet(() -> new CommandReceipt(command.commandId(), transferId));
    }

    @Override
    public Optional<CommandStatus> status(String commandId) {
        if (!LedgerCommand.isAcceptableId(commandId)) {
            return Optional.empty();
        }
        return transactions.inTransaction(() -> store.find(commandId)).map(WorkerWrites::statusOf);
    }

    /**
     * Claims up to limit commands that pass the gate. The earliest time they became sendable goes first; ties go in
     * acceptance order ({@link io.github.ledgerrelay.core.spi.CommandStore#lockDue}). Call it outside a transaction.
     */
    public ClaimBatch claim(int limit, Duration lease) {
        WorkerWrites.requireOutsideTransaction(transactions);
        return transactions.inTransaction(() -> {
            // Advance the head of keys whose head command has finished. If the app holds the key row, do not wait;
            // leave it to the next claim
            keys.passOnFinishedHeads(limit);
            List<ClaimedCommand> claimed = new ArrayList<>();
            List<String> halted = new ArrayList<>();
            List<ClaimBatch.Problem> problems = new ArrayList<>();
            for (DueCommand due : store.lockDue(limit)) {
                CommandRecord candidate = due.command();
                // lockDue returns an in-flight command only when its lease has expired
                boolean leaseExpired = candidate.row().state() == StoredState.CLAIMED;
                GateDecision gate;
                StepResult result;
                HaltReason haltReason = HaltReason.MALFORMED_ROW;
                try {
                    gate = OrderingGate.decide(candidate.kind(), candidate.reservationCommandId(), candidate.seq(),
                            due.reservation().stream().toList());
                    result = CommandStateMachine.apply(candidate.row(), new Step.Claim(gate, leaseExpired));
                    if (gate == GateDecision.HALT) {
                        haltReason = OrderingGate.explainHalt(candidate.kind(), candidate.reservationCommandId(),
                                candidate.seq(), due.reservation().stream().toList());
                    }
                } catch (RuntimeException malformed) {
                    // The row data broke the rules (direct DB edit, store bug). The decision is pure Java, so the
                    // transaction is
                    // intact. Halt only that row so only its key is blocked, and keep deciding the other candidates
                    // (F29).
                    // If even halting fails, pass over it. Either way, report it with the cause
                    gate = GateDecision.HALT;
                    try {
                        result = CommandStateMachine.apply(candidate.row(), new Step.Claim(gate, leaseExpired));
                    } catch (RuntimeException unparkable) {
                        malformed.addSuppressed(unparkable);
                        problems.add(new ClaimBatch.Problem(candidate.commandId(), malformed, false));
                        continue;
                    }
                    problems.add(new ClaimBatch.Problem(candidate.commandId(), malformed, true));
                }
                if (!(result instanceof StepResult.Moved moved)) {
                    continue;
                }
                // Park a command to halt as HALTED so only its key is blocked. It is never a candidate again, so it is
                // reported once, here (F9)
                boolean halting = moved.row().state() == StoredState.HALTED;
                RowChange.Stamp stamp = halting ? new RowChange.Stamp.Halt(haltReason) : new RowChange.Stamp.LeaseFor(lease);
                if (!store.compareAndSet(new RowChange(candidate.rowId(), candidate.row().state(),
                        candidate.row().leaseToken(), moved.row(), stamp))) {
                    continue;
                }
                if (halting) {
                    halted.add(candidate.commandId());
                } else {
                    claimed.add(claimedOf(candidate, moved.row(), gate == GateDecision.SKIP));
                }
            }
            return new ClaimBatch(claimed, halted, problems);
        });
    }

    private static ClaimedCommand claimedOf(CommandRecord command, CommandRow claimedRow, boolean skip) {
        Submission submission = new Submission(command.transferId(), BigInteger.valueOf(command.rowId()),
                command.body());
        return new ClaimedCommand(command.rowId(), command.commandId(), command.orderingKey(),
                claimedRow.leaseToken(), skip, claimedRow.knownResult(), submission);
    }

    /** Records the submission right before sending to TB. Call it outside a transaction. */
    public WriteResult markSubmitted(ClaimedCommand claimed) {
        return workerWrites.markSubmitted(claimed);
    }

    /**
     * Ends the command with the TB result and runs the handler in the same transaction (G6). Call it outside a
     * transaction. If the handler throws an Error, it records the received result in a new transaction and then
     * rethrows that Error unchanged.
     */
    public WriteResult complete(ClaimedCommand claimed, LedgerResultCode code) {
        return workerWrites.complete(claimed, code);
    }

    /**
     * Skips because the referenced reservation was not applied, and runs the handler in the same transaction. Call it
     * outside a transaction.
     */
    public WriteResult skip(ClaimedCommand claimed) {
        return workerWrites.skip(claimed);
    }

    /**
     * Puts the command back after no response or an exception. It is claimed again only after the backoff. Call it
     * outside a transaction.
     */
    public WriteResult release(ClaimedCommand claimed) {
        return workerWrites.release(claimed);
    }

    /**
     * An operator abandons a halted (HALTED) command (docs/failure-modes.md F9). The command ends as skipped without
     * being sent to TB, and in the same transaction the handler receives the reason "abandoned by operator". If the
     * handler fails, the command stays halted. Call it outside a transaction.
     *
     * @throws io.github.ledgerrelay.core.CommandNotFoundException the command was never accepted
     * @throws io.github.ledgerrelay.core.CannotAbandonException   the command is not halted, or it was already sent to
     *                                                             TB (F27)
     */
    public WriteResult abandonHalted(String commandId) {
        return workerWrites.abandonHalted(commandId);
    }

    private BigInteger reservationTransferId(LedgerCommand command, String reservationId) {
        return store.find(reservationId)
                .filter(reservation -> !reservationId.equals(command.commandId()))
                .filter(reservation -> reservation.orderingKey().equals(command.orderingKey()))
                .filter(reservation -> reservation.kind() == CommandKind.RESERVE)
                .filter(reservation -> !reservation.acceptedInThisTransaction())
                .map(CommandRecord::transferId)
                .orElseThrow(() -> new ReservationNotFoundException(command.commandId(), reservationId,
                        command.orderingKey()));
    }

    private static CommandReceipt sameOrConflict(CommandRecord existing, String hash) {
        if (!existing.contentHash().equals(hash)) {
            throw new CommandConflictException(existing.commandId());
        }
        return new CommandReceipt(existing.commandId(), existing.transferId());
    }
}
