package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.QueuedCommand;
import io.github.ledgerrelay.core.spi.Backlog;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.HaltMark;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The in-memory store. The reference implementation for the contract tests, and the store for the stage 6 randomized
 * checks (E6a). It makes no decisions.
 * A transaction is one lock plus a snapshot. Only one transaction runs at a time, so SKIP LOCKED has nothing to do.
 */
public final class InMemoryCommandStore implements OrderingKeys, CommandStore, Transactions {

    private final Supplier<Instant> clock;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Row id → row. Keeps insertion order (= acceptance order). Rows are immutable; a change replaces the whole row.
     */
    private Map<Long, Row> rows = new LinkedHashMap<>();
    private Map<String, Long> rowIdByCommandId = new HashMap<>();
    private Map<String, Long> lastSeqByKey = new HashMap<>();
    private long lastRowId;
    /** Goes up each time an outer transaction opens. A rollback does not undo it */
    private long transactionNumber;

    public InMemoryCommandStore(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (lock.isHeldByCurrentThread()) {
            return work.get(); // Join. The outer transaction owns the rollback
        }
        lock.lock();
        transactionNumber++;
        Snapshot before = snapshot();
        try {
            return work.get();
        } catch (RuntimeException | Error e) {
            restore(before);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void requireActive() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Cannot accept a command outside a transaction (G1). Call submit() inside the app transaction");
        }
    }

    @Override
    public boolean isActive() {
        return lock.isHeldByCurrentThread();
    }

    /** Test only: deletes one row (models the retention job). Call it outside a transaction. */
    public void delete(String commandId) {
        lock.lock();
        try {
            Long rowId = rowIdByCommandId.remove(commandId);
            if (rowId == null) {
                throw new IllegalStateException("given setup failed: no " + commandId + " to delete");
            }
            rows.remove(rowId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long lockKeyAndTakeSeq(String orderingKey) {
        requireTransaction();
        return lastSeqByKey.merge(orderingKey, 1L, Long::sum);
    }

    /** The head is computed from the rows on every {@link #lockDue}, so there is no marker to advance. */
    @Override
    public void passOnFinishedHeads(int limit) {
        requireTransaction();
    }

    @Override
    public Optional<CommandRecord> insertIfAbsent(NewCommand c) {
        requireTransaction();
        Long existing = rowIdByCommandId.get(c.commandId());
        if (existing != null) {
            return Optional.of(recordOf(rows.get(existing)));
        }
        long rowId = ++lastRowId;
        Instant now = clock.get();
        CommandRecord record = new CommandRecord(rowId, c.commandId(), c.orderingKey(), c.seq(),
                c.reservationCommandId(), c.transferId(), c.contentHash(), c.body(), CommandRow.accepted(), now,
                Optional.empty(), false, Optional.empty());
        rows.put(rowId, new Row(record, null, now, transactionNumber));
        rowIdByCommandId.put(c.commandId(), rowId);
        return Optional.empty();
    }

    @Override
    public Backlog backlog() {
        requireTransaction();
        long unfinished = 0;
        long halted = 0;
        Optional<Instant> oldest = Optional.empty();
        for (Row row : rows.values()) {
            switch (row.record().row().state()) {
                case PENDING, CLAIMED -> {
                    unfinished++;
                    Instant accepted = row.record().acceptedAt();
                    oldest = oldest.filter(o -> !o.isAfter(accepted)).or(() -> Optional.of(accepted));
                }
                case HALTED -> halted++;
                default -> {
                }
            }
        }
        return new Backlog(unfinished, halted, oldest, false);
    }

    @Override
    public List<CommandRecord> findFinished(long afterRowId, int limit) {
        requireTransaction();
        return rows.values().stream()
                .filter(row -> row.record().rowId() > afterRowId)
                .filter(row -> row.record().row().state().isFinal())
                .sorted(Comparator.comparingLong(row -> row.record().rowId()))
                .limit(limit).map(this::recordOf).toList();
    }

    @Override
    public List<CommandRecord> findHalted(long afterRowId, int limit) {
        requireTransaction();
        return rows.values().stream()
                .filter(row -> row.record().rowId() > afterRowId)
                .filter(row -> row.record().row().state() == io.github.ledgerrelay.core.domain.StoredState.HALTED)
                .sorted(Comparator.comparingLong(row -> row.record().rowId()))
                .limit(limit).map(this::recordOf).toList();
    }

    @Override
    public Optional<CommandRecord> find(String commandId) {
        requireTransaction();
        return Optional.ofNullable(rowIdByCommandId.get(commandId)).map(rows::get).map(this::recordOf);
    }

    @Override
    public List<DueCommand> lockDue(int limit) {
        requireTransaction();
        Instant now = clock.get();
        // The unfinished head (smallest seq) command of each ordering key
        Map<String, Long> headSeqByKey = new HashMap<>();
        for (Row row : rows.values()) {
            CommandRecord r = row.record();
            if (!r.row().state().isFinal()) {
                headSeqByKey.merge(r.orderingKey(), r.seq(), Math::min);
            }
        }
        // In the order they became sendable; ties in acceptance order
        List<Row> heads = new ArrayList<>();
        for (Row row : rows.values()) {
            CommandRecord r = row.record();
            boolean isHead = !r.row().state().isFinal() && headSeqByKey.get(r.orderingKey()) == r.seq();
            if (isHead && row.isDue(now)) {
                heads.add(row);
            }
        }
        heads.sort(Comparator.comparing(Row::readyAt).thenComparingLong(row -> row.record().rowId()));
        return heads.stream().limit(limit).map(row -> new DueCommand(recordOf(row), reservationOf(row.record())))
                .toList();
    }

    @Override
    public boolean compareAndSet(RowChange change) {
        requireTransaction();
        Row row = rows.get(change.rowId());
        if (row == null) {
            return false;
        }
        CommandRow current = row.record().row();
        if (current.state() != change.expectedState() || current.leaseToken() != change.expectedToken()) {
            return false;
        }
        Instant now = clock.get();
        Row next = switch (change.stamp()) {
            case RowChange.Stamp.LeaseFor lease -> row.with(change.next(), now.plus(lease.lease()), row.nextAttemptAt(),
                    row.record().completedAt(), Optional.empty());
            // first_submitted_at is not part of the query result, so the in-memory store does not record it
            case RowChange.Stamp.FirstSubmission submission -> row.with(change.next(), row.leaseExpiresAt(),
                    row.nextAttemptAt(), row.record().completedAt(), Optional.empty());
            case RowChange.Stamp.RetryAfter retry -> row.with(change.next(), row.leaseExpiresAt(),
                    now.plus(retry.backoff()), row.record().completedAt(), Optional.empty());
            case RowChange.Stamp.Completion completion -> row.with(change.next(), row.leaseExpiresAt(),
                    row.nextAttemptAt(), Optional.of(now), Optional.empty());
            case RowChange.Stamp.Halt halt -> row.with(change.next(), row.leaseExpiresAt(), row.nextAttemptAt(),
                    row.record().completedAt(), Optional.of(new HaltMark(halt.reason(), now)));
        };
        rows.put(change.rowId(), next);
        return true;
    }

    private Optional<QueuedCommand> reservationOf(CommandRecord command) {
        return command.reservationCommandId()
                .map(rowIdByCommandId::get)
                .map(rows::get)
                .map(Row::record)
                .map(reservation -> new QueuedCommand(reservation.commandId(), reservation.seq(),
                        reservation.row().state()));
    }

    /** Returns the stored record with "did the current transaction insert it" attached */
    private CommandRecord recordOf(Row row) {
        CommandRecord r = row.record();
        return new CommandRecord(r.rowId(), r.commandId(), r.orderingKey(), r.seq(), r.reservationCommandId(),
                r.transferId(), r.contentHash(), r.body(), r.row(), r.acceptedAt(), r.completedAt(),
                row.acceptedIn() == transactionNumber, r.halt());
    }

    private void requireTransaction() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Bug: call the store only inside a transaction");
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(new LinkedHashMap<>(rows), new HashMap<>(rowIdByCommandId), new HashMap<>(lastSeqByKey),
                lastRowId);
    }

    private void restore(Snapshot s) {
        rows = s.rows();
        rowIdByCommandId = s.rowIdByCommandId();
        lastSeqByKey = s.lastSeqByKey();
        lastRowId = s.lastRowId();
    }

    private record Snapshot(Map<Long, Row> rows, Map<String, Long> rowIdByCommandId, Map<String, Long> lastSeqByKey,
            long lastRowId) {
    }

    /**
     * A stored row, plus the timestamp columns only the store uses.
     *
     * @param leaseExpiresAt null before a claim
     * @param nextAttemptAt  when an accepted row becomes a claim candidate
     * @param acceptedIn     the number of the transaction that inserted it
     */
    private record Row(CommandRecord record, Instant leaseExpiresAt, Instant nextAttemptAt, long acceptedIn) {

        /**
         * Whether it is time for it to be a claim candidate: for an accepted row when the next attempt time arrives, in
         * progress when the lease expires.
         */
        boolean isDue(Instant now) {
            return switch (record.row().state()) {
                case PENDING -> !nextAttemptAt.isAfter(now);
                case CLAIMED -> leaseExpiresAt.isBefore(now);
                // A halted command is not a candidate until an operator resolves it. It is the head, so the later
                // commands of its key are not candidates either
                case APPLIED, REJECTED, CONFLICT, SKIPPED, HALTED -> false;
            };
        }

        /**
         * When it becomes sendable: the lease expiry when in progress, otherwise the next attempt time (same as
         * ready_at in jdbc).
         */
        Instant readyAt() {
            return record.row().state() == io.github.ledgerrelay.core.domain.StoredState.CLAIMED ? leaseExpiresAt
                    : nextAttemptAt;
        }

        Row with(CommandRow newRow, Instant newLeaseExpiresAt, Instant newNextAttemptAt, Optional<Instant> completedAt,
                Optional<HaltMark> halt) {
            CommandRecord r = record;
            return new Row(new CommandRecord(r.rowId(), r.commandId(), r.orderingKey(), r.seq(),
                    r.reservationCommandId(), r.transferId(), r.contentHash(), r.body(), newRow, r.acceptedAt(),
                    completedAt, false, halt), newLeaseExpiresAt, newNextAttemptAt, acceptedIn);
        }
    }
}
