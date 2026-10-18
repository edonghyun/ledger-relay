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
 * 릴레이의 health. TB가 끊겨도 상태는 UP이다: 접수는 TB 없이도 되는 것이 이 라이브러리의 요점이고, health가 DOWN이면
 * 부하 분산기나 오케스트레이터가 접수까지 막는다. TB 상태는 세부 항목 {@code ledger}로만 알린다. 저장소를 읽을 수 없을 때만
 * DOWN이다(PG 없이는 접수도 못 한다).
 *
 * <p>TB 확인은 health 요청을 붙잡지 않는다. 요청마다 조회를 하나 보내되(이미 하나가 가 있으면 보내지 않는다), 답은 기다리지
 * 않는다. 마지막으로 끝난 확인이 성공했고, 가 있는 확인이 {@code ledgerStaleAfter}보다 오래 답을 기다리지 않으면 UP이다. TB가 끊긴 동안 health가 probe
 * timeout에 걸려 앱이 서비스에서 빠지거나, 조회가 TB 클라이언트에 끝없이 쌓이지 않게 하려는 것이다.
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

    /** @param ledgerStaleAfter TB의 마지막 답이 이보다 오래됐으면 DOWN으로 본다 */
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
     * TB 클라이언트에는 ping이 없어 이체 하나를 조회해 본다. 기다리지 않는다. 마지막으로 끝난 확인이 성공했고, 지금 가 있는 확인이
     * {@code ledgerStaleAfter}보다 오래 답을 기다리고 있지 않으면 닿는다고 본다. "마지막 답이 지금부터 얼마 안인가"로 보면
     * health를 그보다 드물게 부르는 환경(Kubernetes probe 기본 10초)에서 TB가 멀쩡해도 늘 DOWN이 된다(F40).
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
        // 바로 답한 확인이면 그 결과, 아니면 직전에 끝난 확인의 결과다. 막 보낸 확인은 아직 늦지 않았다
        return lastProbeSucceeded;
    }
}
