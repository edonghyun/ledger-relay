package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.QueuedCommand;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 메모리 저장소. 계약 테스트의 기준 구현이자 6단계 무작위 검증(E6a)의 저장소다. 판단은 하지 않는다.
 * 트랜잭션은 잠금 하나와 스냅샷이다. 한 번에 한 트랜잭션만 돌므로 SKIP LOCKED가 할 일이 없다.
 */
public final class InMemoryCommandStore implements CommandStore, Transactions {

    private final Supplier<Instant> clock;
    private final ReentrantLock lock = new ReentrantLock();

    /** 행 ID → 행. 넣은 순서(= 접수 순서)를 지킨다. 행은 불변이며, 바꿀 때는 통째로 갈아 끼운다. */
    private Map<Long, Row> rows = new LinkedHashMap<>();
    private Map<String, Long> rowIdByCommandId = new HashMap<>();
    private Map<String, Long> lastSeqByKey = new HashMap<>();
    private long lastRowId;

    public InMemoryCommandStore(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (lock.isHeldByCurrentThread()) {
            return work.get(); // 합류. 바깥 트랜잭션이 롤백을 맡는다
        }
        lock.lock();
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
            throw new IllegalStateException("트랜잭션 밖에서는 명령을 접수할 수 없습니다(G1). 앱 트랜잭션 안에서 submit()을 부르세요");
        }
    }

    @Override
    public boolean isActive() {
        return lock.isHeldByCurrentThread();
    }

    @Override
    public long nextSeq(String orderingKey) {
        requireTransaction();
        return lastSeqByKey.merge(orderingKey, 1L, Long::sum);
    }

    @Override
    public Optional<CommandRecord> insertIfAbsent(NewCommand c) {
        requireTransaction();
        Long existing = rowIdByCommandId.get(c.commandId());
        if (existing != null) {
            return Optional.of(rows.get(existing).record());
        }
        long rowId = ++lastRowId;
        Instant now = clock.get();
        CommandRecord record = new CommandRecord(rowId, c.commandId(), c.orderingKey(), c.seq(),
                c.reservationCommandId(), c.transferId(), c.contentHash(), c.body(), CommandRow.accepted(), now,
                Optional.empty());
        rows.put(rowId, new Row(record, null, now));
        rowIdByCommandId.put(c.commandId(), rowId);
        return Optional.empty();
    }

    @Override
    public Optional<CommandRecord> find(String commandId) {
        requireTransaction();
        return Optional.ofNullable(rowIdByCommandId.get(commandId)).map(rows::get).map(Row::record);
    }

    @Override
    public List<DueCommand> lockDue(int limit) {
        requireTransaction();
        Instant now = clock.get();
        // 순서 키마다 맨 앞(가장 작은 seq)의 미완료 명령(P2a-19)
        Map<String, Long> headSeqByKey = new HashMap<>();
        for (Row row : rows.values()) {
            CommandRecord r = row.record();
            if (!r.row().state().isFinal()) {
                headSeqByKey.merge(r.orderingKey(), r.seq(), Math::min);
            }
        }
        List<DueCommand> due = new ArrayList<>();
        for (Row row : rows.values()) {
            if (due.size() >= limit) {
                break;
            }
            CommandRecord r = row.record();
            boolean isHead = !r.row().state().isFinal() && headSeqByKey.get(r.orderingKey()) == r.seq();
            if (isHead && row.isDue(now)) {
                due.add(new DueCommand(r, reservationOf(r)));
            }
        }
        return due;
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
                    row.record().completedAt());
            // first_submitted_at은 조회 결과에 나가지 않으므로 메모리 저장소는 기록하지 않는다
            case RowChange.Stamp.FirstSubmission submission -> row.with(change.next(), row.leaseExpiresAt(),
                    row.nextAttemptAt(), row.record().completedAt());
            case RowChange.Stamp.RetryAfter retry -> row.with(change.next(), row.leaseExpiresAt(),
                    now.plus(retry.backoff()), row.record().completedAt());
            case RowChange.Stamp.Completion completion -> row.with(change.next(), row.leaseExpiresAt(),
                    row.nextAttemptAt(), Optional.of(now));
            case RowChange.Stamp.Halt halt -> row.with(change.next(), row.leaseExpiresAt(), row.nextAttemptAt(),
                    row.record().completedAt());
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

    private void requireTransaction() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("버그: 저장소는 트랜잭션 안에서만 부른다");
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
     * 저장된 행과, 저장소만 쓰는 시각 컬럼.
     *
     * @param leaseExpiresAt claim 전에는 null
     * @param nextAttemptAt  접수 상태의 행이 claim 후보가 되는 시각
     */
    private record Row(CommandRecord record, Instant leaseExpiresAt, Instant nextAttemptAt) {

        /** claim 후보가 될 시각이 됐는가(§5.2). 접수 상태는 다음 시도 시각이 됐을 때, 처리 중은 lease가 끝났을 때. */
        boolean isDue(Instant now) {
            return switch (record.row().state()) {
                case PENDING -> !nextAttemptAt.isAfter(now);
                case CLAIMED -> leaseExpiresAt.isBefore(now);
                // 멈춘 명령은 운영자가 풀 때까지 후보가 아니다. 맨 앞이므로 그 키의 뒤 명령도 후보가 되지 않는다
                case APPLIED, REJECTED, CONFLICT, SKIPPED, HALTED -> false;
            };
        }

        Row with(CommandRow newRow, Instant newLeaseExpiresAt, Instant newNextAttemptAt, Optional<Instant> completedAt) {
            CommandRecord r = record;
            return new Row(new CommandRecord(r.rowId(), r.commandId(), r.orderingKey(), r.seq(),
                    r.reservationCommandId(), r.transferId(), r.contentHash(), r.body(), newRow, r.acceptedAt(),
                    completedAt), newLeaseExpiresAt, newNextAttemptAt);
        }
    }
}
