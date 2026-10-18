package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.AbandonOutcome;
import io.github.ledgerrelay.core.CannotAbandonException;
import io.github.ledgerrelay.core.CommandBacklog;
import io.github.ledgerrelay.core.HaltedCommand;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.ReconciliationReport;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.HaltMark;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.LedgerTransfer;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.spi.WaitingCounts;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The implementation of the operator port. It never locks ordering key rows, so it does not take
 * {@link io.github.ledgerrelay.core.spi.OrderingKeys}. Abandoning is delegated to
 * {@link CommandLifecycle#abandonHalted}, which calls the handler (F33).
 */
public final class RelayOperator implements LedgerRelayOperator {

    static final int MAX_PAGE = 1000;

    private final CommandStore store;
    private final Transactions transactions;
    private final CommandLifecycle lifecycle;
    private final LedgerClient ledger;
    private final Duration lookupTimeout;

    public RelayOperator(CommandStore store, Transactions transactions, CommandLifecycle lifecycle, LedgerClient ledger,
            Duration lookupTimeout) {
        this.store = store;
        this.transactions = transactions;
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.lookupTimeout = lookupTimeout;
    }

    @Override
    public CommandBacklog backlog() {
        WaitingCounts counts = transactions.inTransaction(store::waitingCounts);
        // A row accepted after the store read its clock would give a negative age. Report it as zero
        Optional<Duration> age = counts.oldestWaitingAcceptedAt()
                .map(accepted -> Duration.between(accepted, counts.readAt()))
                .map(between -> between.isNegative() ? Duration.ZERO : between);
        return new CommandBacklog(counts.pending(), counts.claimed(), counts.halted(), age, counts.readAt());
    }

    /**
     * Queries TB after closing the transaction that read the page, so a slow TB never makes it wait while holding a DB
     * connection. Verdict: an applied command (APPLIED) must exist in TB and be the same. A rejected or skipped command
     * must not exist in TB. A conflict is not checked, because the TB transfer with that id belongs to another
     * command.
     */
    @Override
    public ReconciliationReport reconcile(long afterPosition, int limit) {
        requirePage(limit);
        List<CommandRecord> page = transactions.inTransaction(() -> store.findFinished(afterPosition, limit));
        if (page.isEmpty()) {
            return new ReconciliationReport(0, 0, List.of(), afterPosition);
        }
        List<CommandRecord> checkable = page.stream().filter(r -> r.row().state() != StoredState.CONFLICT).toList();
        Map<BigInteger, LedgerTransfer> inLedger = lookUp(checkable.stream().map(CommandRecord::transferId).toList());
        long matched = 0;
        List<ReconciliationReport.Mismatch> mismatches = new ArrayList<>();
        for (CommandRecord record : checkable) {
            Optional<ReconciliationReport.Mismatch.Kind> mismatch = compare(record, inLedger.get(record.transferId()));
            if (mismatch.isEmpty()) {
                matched++;
            } else if (mismatches.size() < ReconciliationReport.MAX_MISMATCHES) {
                mismatches.add(new ReconciliationReport.Mismatch(record.commandId(), record.transferId(),
                        mismatch.get()));
            }
        }
        return new ReconciliationReport(checkable.size(), matched, mismatches, page.get(page.size() - 1).rowId());
    }

    private Map<BigInteger, LedgerTransfer> lookUp(List<BigInteger> transferIds) {
        if (transferIds.isEmpty()) {
            return Map.of();
        }
        try {
            return ledger.lookup(transferIds).get(lookupTimeout.toNanos(), TimeUnit.NANOSECONDS).stream()
                    .collect(Collectors.toMap(LedgerTransfer::transferId, t -> t, (a, b) -> a));
        } catch (TimeoutException e) {
            throw new LedgerUnavailableException("TB did not answer the lookup within " + lookupTimeout, e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during reconciliation", e);
        }
    }

    private static Optional<ReconciliationReport.Mismatch.Kind> compare(CommandRecord record, LedgerTransfer found) {
        if (record.row().state() != StoredState.APPLIED) {
            return found == null ? Optional.empty() : Optional.of(ReconciliationReport.Mismatch.Kind.UNEXPECTED_IN_LEDGER);
        }
        if (found == null) {
            return Optional.of(ReconciliationReport.Mismatch.Kind.MISSING_IN_LEDGER);
        }
        return sameTransfer(record.body(), found) ? Optional.empty()
                : Optional.of(ReconciliationReport.Mismatch.Kind.DIFFERENT);
    }

    /**
     * post and void inherit the accounts, ledger and code from the reservation, so only the kind is compared. A post
     * with a set amount also compares the amount.
     */
    private static boolean sameTransfer(TransferBody body, LedgerTransfer found) {
        if (body.kind() != found.kind()) {
            return false;
        }
        return switch (body.kind()) {
            case TRANSFER, RESERVE -> body.debitAccountId().equals(found.debitAccountId())
                    && body.creditAccountId().equals(found.creditAccountId()) && body.amount().equals(found.amount())
                    && body.ledger() == found.ledger() && body.code() == found.code();
            case POST -> body.amount().equals(LedgerCommand.AMOUNT_MAX) || body.amount().equals(found.amount());
            case VOID -> true;
        };
    }

    @Override
    public List<HaltedCommand> haltedCommands(long afterPosition, int limit) {
        requirePage(limit);
        return transactions.inTransaction(() -> store.findHalted(afterPosition, limit)).stream()
                .map(RelayOperator::haltedOf).toList();
    }

    @Override
    public AbandonOutcome abandonHalted(String commandId) {
        return switch (lifecycle.abandonHalted(commandId)) {
            case WriteResult.Written written -> AbandonOutcome.ABANDONED;
            case WriteResult.HandlerFailed failed -> AbandonOutcome.HANDLER_FAILED;
            // After the read, another party (another operator's abandon) changed it first. It is no longer halted
            case WriteResult.Fenced fenced -> throw new CannotAbandonException(commandId,
                    CannotAbandonException.Reason.NOT_HALTED, "Another party changed the command first; it is no longer halted: " + commandId);
        };
    }

    static void requirePage(int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE + ": " + limit);
        }
    }

    private static HaltedCommand haltedOf(CommandRecord record) {
        HaltMark halt = record.halt().orElseThrow(() -> new IllegalStateException(
                "Bug: halted command " + record.commandId() + " has no halt record"));
        return new HaltedCommand(record.rowId(), record.commandId(), record.orderingKey(), record.seq(), record.kind(),
                halt.reason(), halt.haltedAt());
    }
}
