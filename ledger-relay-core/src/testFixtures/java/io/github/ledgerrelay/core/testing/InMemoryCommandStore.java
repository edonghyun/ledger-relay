package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.DueCommand;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.RowChange;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Instant;
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
        return List.of();
    }

    @Override
    public boolean compareAndSet(RowChange change) {
        requireTransaction();
        return false;
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
    }
}
