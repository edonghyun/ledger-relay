package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.spi.Backlog;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.Transactions;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Health of the relay. The status stays UP when TB is unreachable: the point of this library is that acceptance works
 * without TB, and a DOWN health would make a load balancer or orchestrator block acceptance too. TB status is reported
 * only in the {@code ledger} detail. The status is DOWN only when the store cannot be read (without PG, nothing can be
 * accepted).
 *
 * <p>The TB check does not hold up the health request. Each request sends one lookup (none if one is already
 * outstanding) and does not wait for the answer. The result is UP when the last finished check succeeded and the
 * outstanding check has not waited longer than {@code ledgerStaleAfter}. This keeps health from hitting the probe
 * timeout while TB is unreachable, which would take the app out of service, and keeps lookups from piling up in the TB
 * client without end.
 */
public final class LedgerRelayHealthIndicator implements HealthIndicator {

    private final CommandStore store;
    private final Transactions transactions;
    private final LedgerClient ledger;
    private final Duration ledgerStaleAfter;
    private final Supplier<Instant> clock;
    private CompletableFuture<?> probe;
    private Instant probeSentAt;
    private volatile boolean lastProbeSucceeded;

    /** @param ledgerStaleAfter TB is considered DOWN if its last answer is older than this */
    public LedgerRelayHealthIndicator(CommandStore store, Transactions transactions, LedgerClient ledger,
            Duration ledgerStaleAfter) {
        this(store, transactions, ledger, ledgerStaleAfter, Instant::now);
    }

    LedgerRelayHealthIndicator(CommandStore store, Transactions transactions, LedgerClient ledger,
            Duration ledgerStaleAfter, Supplier<Instant> clock) {
        this.store = store;
        this.transactions = transactions;
        this.ledger = ledger;
        this.ledgerStaleAfter = ledgerStaleAfter;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Backlog backlog;
        try {
            backlog = transactions.inTransaction(store::backlog);
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
        Health.Builder health = Health.up()
                .withDetail("ledger", ledgerAnswers() ? "UP" : "DOWN")
                .withDetail("unfinished", backlog.unfinished())
                .withDetail("halted", backlog.halted())
                .withDetail("countsCapped", backlog.countsCapped());
        backlog.oldestUnfinishedAcceptedAt().ifPresent(oldest -> health.withDetail("oldestUnfinishedAgeSeconds",
                Duration.between(oldest, clock.get()).toSeconds()));
        return health.build();
    }

    /**
     * The TB client has no ping, so the check looks up one transfer. It does not wait. TB counts as reachable when the
     * last finished check succeeded and the current outstanding check has not waited longer than
     * {@code ledgerStaleAfter}. Checking "how recent is the last answer" instead would always report DOWN, even with a
     * healthy TB, in environments that call health less often than that (Kubernetes probe default: 10 seconds) (F40).
     */
    private synchronized boolean ledgerAnswers() {
        Instant now = clock.get();
        if (probe != null && !probe.isDone()) {
            return lastProbeSucceeded && Duration.between(probeSentAt, now).compareTo(ledgerStaleAfter) <= 0;
        }
        probeSentAt = now;
        try {
            probe = ledger.lookup(List.of(BigInteger.ONE))
                    .whenComplete((found, failure) -> lastProbeSucceeded = failure == null);
        } catch (RuntimeException e) {
            probe = null;
            lastProbeSucceeded = false;
        }
        // Result of a check that answered at once, else of the last finished check. A check just sent is not late yet
        return lastProbeSucceeded;
    }
}
