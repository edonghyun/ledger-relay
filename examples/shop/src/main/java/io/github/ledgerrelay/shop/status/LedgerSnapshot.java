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
import java.util.Set;
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
 * Collects the UI numbers read from TB (balances, orders charged twice, reconciliation) every second on a dedicated thread.
 * The API returns the latest collected values immediately.
 *
 * <p>The TB client has no timeout. When TB is unreachable, a lookup waits until the connection returns. If UI requests
 * called TB directly, the UI would freeze in scenario 2 and a new lookup would pile up in the client every second. So
 * this one thread is the only caller of TB, and it does not start a new refresh while the previous one is unfinished.
 * Meanwhile the API returns the last values marked {@code stale}.
 */
@Component
class LedgerSnapshot {

    private static final Log log = LogFactory.getLog(LedgerSnapshot.class);
    /** Values older than this are shown as stale in the UI. */
    static final Duration STALE_AFTER = Duration.ofSeconds(3);
    /**
     * The verdict is broken only after this many unequal refreshes in a row with no post in flight. A single one may
     * have caught the gap between the TB apply and the PG commit.
     */
    static final int BROKEN_AFTER = 3;
    /**
     * Pages reconciled per refresh (1,000 commands each). At demo size this always covers everything; beyond it only the
     * first 50,000 are checked.
     */
    private static final int RECONCILE_PAGES = 50;
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

    // Position and running totals of the double-transfer count. Only the refresh thread uses them
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
                    log.debug("Could not refresh the ledger numbers; keeping the last values", e);
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
        // Read PG first. TB can only be ahead of PG (TB apply happens before the PG commit)
        long paid = stats.paidOrdersSatang();
        long capturesInFlight = stats.capturesInFlight();
        Map<Long, AccountBalance> balances = new HashMap<>();
        balances.put(Accounts.MERCHANT, Accounts.balanceOf(client, Accounts.MERCHANT));
        for (long buyer : Accounts.BUYERS) {
            balances.put(buyer, Accounts.balanceOf(client, buyer));
        }
        Reconciled reconciled = reconcileAll();
        long doubleTransfers = countDoubleTransfers();
        long posted = balances.get(Accounts.MERCHANT).creditsPosted();
        boolean equal = posted == paid;
        unequalInARow = equal ? 0 : unequalInARow + 1;
        String verdict;
        if (reconciled.mismatches() > 0 || doubleTransfers > 0) {
            verdict = "broken";
        } else if (equal) {
            verdict = "ok";
        } else {
            verdict = capturesInFlight > 0 || unequalInARow < BROKEN_AFTER ? "settling" : "broken";
        }
        return new Snapshot(Instant.now(), Map.copyOf(balances), doubleTransfers, reconciled.checked(), reconciled.matched(),
                reconciled.mismatches(), posted, paid, verdict);
    }

    private record Reconciled(long checked, long matched, long mismatches) {
    }

    /**
     * Reconciles every completed command from the start. Continuing from the last position is wrong: commands do not
     * complete in row order (a post that halted and was abandoned, batches of two workers, a transaction that committed
     * late). If the position passes a row that is not yet complete, that row is never reconciled, even after it
     * completes.
     */
    private Reconciled reconcileAll() {
        long after = 0;
        long checked = 0;
        long matched = 0;
        for (int page = 0; page < RECONCILE_PAGES; page++) {
            ReconciliationReport report = operator.reconcile(after, 1000);
            if (report.lastPosition() == after) {
                break;
            }
            checked += report.checked();
            matched += report.matched();
            after = report.lastPosition();
        }
        return new Reconciled(checked, matched, checked - matched);
    }

    /**
     * Sums the posting transfers credited to the merchant per order (the transfer's user_data_64) and counts the orders
     * that received more than the order amount. It does not count "the same id twice", which TB's transfer id
     * uniqueness keeps at 0. It counts two posts for the same order under different ids. Reads only new transfers and
     * keeps adding.
     */
    private long countDoubleTransfers() {
        addNewMerchantPosts();
        if (capturedByOrder.isEmpty()) {
            return 0;
        }
        Map<Long, Long> amounts = orderAmounts(capturedByOrder.keySet());
        return capturedByOrder.entrySet().stream()
                .filter(captured -> captured.getValue() > amounts.getOrDefault(captured.getKey(), 0L)).count();
    }

    /** Reads the merchant's credits after the last one read, page by page, and adds each post to its order's total. */
    private void addNewMerchantPosts() {
        int read;
        do {
            TransferBatch page = merchantCreditsAfter(transfersAfter);
            read = 0;
            while (page.next()) {
                read++;
                transfersAfter = page.getTimestamp();
                if ((page.getFlags() & TransferFlags.POST_PENDING_TRANSFER) != 0) {
                    capturedByOrder.merge(page.getUserData64(), page.getAmount().longValueExact(), Long::sum);
                }
            }
        } while (read == TRANSFER_PAGE);
    }

    private TransferBatch merchantCreditsAfter(long timestamp) {
        AccountFilter filter = new AccountFilter();
        filter.setAccountId(Accounts.MERCHANT);
        filter.setCredits(true);
        filter.setTimestampMin(timestamp + 1);
        filter.setLimit(TRANSFER_PAGE);
        try {
            return client.getAccountTransfers(filter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Map<Long, Long> orderAmounts(Set<Long> orderIds) {
        Map<Long, Long> amounts = new HashMap<>();
        sql.sql("SELECT id, amount_satang FROM shop_order WHERE id = ANY(?)").param(orderIds.toArray(Long[]::new))
                .query((row, n) -> amounts.put(row.getLong("id"), row.getLong("amount_satang"))).list();
        return amounts;
    }
}
