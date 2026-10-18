package io.github.ledgerrelay.shop.status;

import com.tigerbeetle.AccountFilter;
import com.tigerbeetle.Client;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.ReconciliationReport;
import io.github.ledgerrelay.shop.Accounts;
import io.github.ledgerrelay.shop.Accounts.AccountBalance;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TB에서 읽는 화면 숫자(잔액, 중복 이체, 대사)를 1초마다 전용 스레드에서 모아 둔다. API는 모아 둔 최신 값을 바로 돌려준다.
 *
 * <p>TB 클라이언트에는 timeout이 없다. TB가 끊기면 조회가 연결이 돌아올 때까지 기다린다. 화면 요청이 TB를 직접 부르면 장면 2에서
 * 화면이 멈추고, 매초 새 조회가 클라이언트에 쌓인다. 그래서 TB를 부르는 것은 이 스레드 하나뿐이고, 앞 갱신이 끝나지 않았으면 새로
 * 시작하지 않는다. 그동안 API는 마지막 값을 {@code stale}로 표시해 돌려준다.
 */
@Component
class LedgerSnapshot {

    private static final Log log = LogFactory.getLog(LedgerSnapshot.class);
    /** 이보다 오래된 값은 화면에 "오래됨"으로 보인다. */
    static final Duration STALE_AFTER = Duration.ofSeconds(3);
    /** 처리 중인 확정 없이 이만큼 연달아 어긋나야 broken이다. 한 번은 TB 적용과 PG 커밋 사이를 본 것일 수 있다. */
    static final int BROKEN_AFTER = 3;
    private static final int RECONCILE_PAGES_PER_REFRESH = 10;
    private static final int TRANSFER_PAGE = 8189;

    record Snapshot(Instant at, Map<Long, AccountBalance> balances, long doubleTransfers, long checked, long matched,
            long mismatches, long merchantPostedSatang, long paidOrdersSatang, String verdict) {
    }

    private final Client client;
    private final LedgerRelayOperator operator;
    private final CommandStats stats;
    private final JdbcClient sql;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "shop-ledger-snapshot"));
    private final AtomicBoolean busy = new AtomicBoolean();

    // 이어서 하는 대사와 중복 이체 세기의 위치. 갱신 스레드만 쓴다
    private long reconciledUpTo;
    private long checked;
    private long matched;
    private long mismatches;
    private long transfersAfter;
    private final Map<Long, Long> capturedByOrder = new HashMap<>();
    private int unequalInARow;

    private volatile Snapshot latest;

    LedgerSnapshot(Client client, LedgerRelayOperator operator, CommandStats stats, DataSource dataSource) {
        this.client = client;
        this.operator = operator;
        this.stats = stats;
        this.sql = JdbcClient.create(dataSource);
    }

    Optional<Snapshot> latest() {
        return Optional.ofNullable(latest);
    }

    boolean isStale(Snapshot snapshot) {
        return snapshot.at().isBefore(Instant.now().minus(STALE_AFTER));
    }

    @Scheduled(fixedDelay = 1000)
    void trigger() {
        if (busy.compareAndSet(false, true)) {
            worker.execute(() -> {
                try {
                    latest = refresh();
                } catch (RuntimeException e) {
                    log.debug("원장 숫자를 갱신하지 못했다. 마지막 값을 그대로 둔다", e);
                } finally {
                    busy.set(false);
                }
            });
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    private Snapshot refresh() {
        // PG를 먼저 읽는다. TB는 PG보다 앞서기만 한다(TB 적용 → PG 커밋 순서)
        long paid = stats.paidOrdersSatang();
        long capturesInFlight = stats.capturesInFlight();
        Map<Long, AccountBalance> balances = new HashMap<>();
        balances.put(Accounts.MERCHANT, Accounts.balanceOf(client, Accounts.MERCHANT));
        for (long buyer : Accounts.BUYERS) {
            balances.put(buyer, Accounts.balanceOf(client, buyer));
        }
        reconcileOnward();
        long doubleTransfers = countDoubleTransfers();
        long posted = balances.get(Accounts.MERCHANT).creditsPosted();
        boolean equal = posted == paid;
        unequalInARow = equal ? 0 : unequalInARow + 1;
        String verdict;
        if (mismatches > 0 || doubleTransfers > 0) {
            verdict = "broken";
        } else if (equal) {
            verdict = "ok";
        } else {
            verdict = capturesInFlight > 0 || unequalInARow < BROKEN_AFTER ? "settling" : "broken";
        }
        return new Snapshot(Instant.now(), Map.copyOf(balances), doubleTransfers, checked, matched, mismatches, posted, paid,
                verdict);
    }

    private void reconcileOnward() {
        for (int page = 0; page < RECONCILE_PAGES_PER_REFRESH; page++) {
            ReconciliationReport report = operator.reconcile(reconciledUpTo, 1000);
            if (report.lastPosition() == reconciledUpTo) {
                return;
            }
            checked += report.checked();
            matched += report.matched();
            mismatches += report.checked() - report.matched();
            reconciledUpTo = report.lastPosition();
        }
    }

    /**
     * 가맹점에 들어온 확정 이체를 주문(이체의 user_data_64)별로 더해, 주문 금액보다 많이 받은 주문 수를 센다. TB의 이체 ID 유일성 때문에
     * 늘 0인 "같은 ID 두 번"을 세지 않는다. 같은 주문에 다른 ID로 확정이 두 번 들어오는 것을 센다. 새 이체만 읽어 이어서 더한다.
     */
    private long countDoubleTransfers() {
        while (true) {
            AccountFilter filter = new AccountFilter();
            filter.setAccountId(Accounts.MERCHANT);
            filter.setCredits(true);
            filter.setTimestampMin(transfersAfter + 1);
            filter.setLimit(TRANSFER_PAGE);
            TransferBatch page;
            try {
                page = client.getAccountTransfers(filter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            int read = 0;
            while (page.next()) {
                read++;
                transfersAfter = page.getTimestamp();
                if ((page.getFlags() & TransferFlags.POST_PENDING_TRANSFER) != 0) {
                    capturedByOrder.merge(page.getUserData64(), page.getAmount().longValueExact(), Long::sum);
                }
            }
            if (read < TRANSFER_PAGE) {
                break;
            }
        }
        if (capturedByOrder.isEmpty()) {
            return 0;
        }
        Map<Long, Long> amounts = new HashMap<>();
        sql.sql("SELECT id, amount_satang FROM shop_order WHERE id = ANY(?)")
                .param(capturedByOrder.keySet().toArray(Long[]::new))
                .query((row, n) -> amounts.put(row.getLong("id"), row.getLong("amount_satang"))).list();
        return capturedByOrder.entrySet().stream()
                .filter(captured -> captured.getValue() > amounts.getOrDefault(captured.getKey(), 0L)).count();
    }
}
