package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.AbandonOutcome;
import io.github.ledgerrelay.core.CannotAbandonException;
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
 * 운영자 포트의 구현. 순서 키 행을 잡지 않으므로 {@link io.github.ledgerrelay.core.spi.OrderingKeys}를 받지 않는다.
 * 포기는 핸들러를 부르는 {@link CommandLifecycle#abandonHalted}에 맡긴다(F33).
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

    /**
     * 페이지를 읽는 트랜잭션을 닫은 뒤 TB를 조회한다. TB가 늦어도 DB 커넥션을 쥐고 기다리지 않게 하려는 것이다.
     * 판정: 적용(APPLIED)은 TB에 있고 같아야 한다. 거절·건너뜀은 TB에 없어야 한다. 충돌은 그 ID의 TB 이체가 남의 것이라
     * 보지 않는다.
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
            throw new LedgerUnavailableException("TB가 " + lookupTimeout + " 안에 조회에 답하지 않았습니다", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대사 중 인터럽트되었다", e);
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

    /** 확정·해제는 계좌·ledger·code를 예약에서 물려받으므로 종류만 본다. 금액을 정한 확정은 금액도 본다. */
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
