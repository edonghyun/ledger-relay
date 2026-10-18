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
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 릴레이의 health(03b). TB가 끊겨도 상태는 UP이다: 접수는 TB 없이도 되는 것이 이 라이브러리의 요점이고, health가 DOWN이면
 * 부하 분산기나 오케스트레이터가 접수까지 막는다. TB 상태는 세부 항목 {@code ledger}로만 알린다. 저장소를 읽을 수 없을 때만
 * DOWN이다(PG 없이는 접수도 못 한다).
 *
 * <p>TB 확인은 health 요청을 붙잡지 않는다. 요청마다 조회를 하나 보내되(이미 하나가 가 있으면 보내지 않는다), 답은 기다리지
 * 않고 마지막으로 답을 받은 시각만 본다. 그 시각이 {@code ledgerStaleAfter} 안이면 UP이다. TB가 끊긴 동안 health가 probe
 * timeout에 걸려 앱이 서비스에서 빠지거나, 조회가 TB 클라이언트에 끝없이 쌓이지 않게 하려는 것이다.
 */
public final class LedgerRelayHealthIndicator implements HealthIndicator {

    private final CommandStore store;
    private final Transactions transactions;
    private final LedgerClient ledger;
    private final Duration ledgerStaleAfter;
    private CompletableFuture<?> probe;
    private volatile Instant lastAnswer;

    /** @param ledgerStaleAfter TB의 마지막 답이 이보다 오래됐으면 DOWN으로 본다 */
    public LedgerRelayHealthIndicator(CommandStore store, Transactions transactions, LedgerClient ledger,
            Duration ledgerStaleAfter) {
        this.store = store;
        this.transactions = transactions;
        this.ledger = ledger;
        this.ledgerStaleAfter = ledgerStaleAfter;
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
                Duration.between(oldest, Instant.now()).toSeconds()));
        return health.build();
    }

    /** TB 클라이언트에는 ping이 없어 이체 하나를 조회해 본다. 기다리지 않고, 마지막 답이 최근이면 닿는다고 본다. */
    private boolean ledgerAnswers() {
        probeIfIdle();
        Instant answered = lastAnswer;
        return answered != null && Duration.between(answered, Instant.now()).compareTo(ledgerStaleAfter) <= 0;
    }

    private synchronized void probeIfIdle() {
        if (probe != null && !probe.isDone()) {
            return;
        }
        try {
            probe = ledger.lookup(List.of(BigInteger.ONE)).whenComplete((found, failure) -> {
                if (failure == null) {
                    lastAnswer = Instant.now();
                }
            });
        } catch (RuntimeException e) {
            probe = null;
        }
    }
}
