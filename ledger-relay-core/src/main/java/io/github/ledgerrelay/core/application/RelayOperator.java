package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.AbandonOutcome;
import io.github.ledgerrelay.core.CannotAbandonException;
import io.github.ledgerrelay.core.HaltedCommand;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.HaltMark;
import io.github.ledgerrelay.core.spi.Transactions;
import java.util.List;

/**
 * 운영자 포트의 구현(03b). 순서 키 행을 잡지 않으므로 {@link io.github.ledgerrelay.core.spi.OrderingKeys}를 받지 않는다.
 * 포기는 핸들러를 부르는 {@link CommandLifecycle#abandonHalted}에 맡긴다(F33).
 */
public final class RelayOperator implements LedgerRelayOperator {

    static final int MAX_PAGE = 1000;

    private final CommandStore store;
    private final Transactions transactions;
    private final CommandLifecycle lifecycle;

    public RelayOperator(CommandStore store, Transactions transactions, CommandLifecycle lifecycle) {
        this.store = store;
        this.transactions = transactions;
        this.lifecycle = lifecycle;
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
            // 읽은 뒤 다른 쪽(다른 운영자의 포기)이 먼저 바꿨다. 이제 멈춘 명령이 아니다
            case WriteResult.Fenced fenced -> throw new CannotAbandonException(commandId,
                    CannotAbandonException.Reason.NOT_HALTED, "다른 쪽이 먼저 바꿔 더는 멈춘 명령이 아닙니다: " + commandId);
        };
    }

    static void requirePage(int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit은 1 이상 " + MAX_PAGE + " 이하여야 합니다: " + limit);
        }
    }

    private static HaltedCommand haltedOf(CommandRecord record) {
        HaltMark halt = record.halt().orElseThrow(() -> new IllegalStateException(
                "버그: 멈춘 명령 " + record.commandId() + "에 멈춘 기록이 없다"));
        return new HaltedCommand(record.rowId(), record.commandId(), record.orderingKey(), record.seq(), record.kind(),
                halt.reason(), halt.haltedAt());
    }
}
