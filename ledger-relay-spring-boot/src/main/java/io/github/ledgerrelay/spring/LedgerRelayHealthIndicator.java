package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.spi.Backlog;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.Transactions;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 릴레이의 health(03b). TB가 끊겨도 상태는 UP이다: 접수는 TB 없이도 되는 것이 이 라이브러리의 요점이고, health가 DOWN이면
 * 부하 분산기나 오케스트레이터가 접수까지 막는다. TB 상태는 세부 항목 {@code ledger}로만 알린다. 저장소를 읽을 수 없을 때만
 * DOWN이다(PG 없이는 접수도 못 한다).
 */
public final class LedgerRelayHealthIndicator implements HealthIndicator {

    private final CommandStore store;
    private final Transactions transactions;
    private final LedgerClient ledger;
    private final Duration probeTimeout;

    public LedgerRelayHealthIndicator(CommandStore store, Transactions transactions, LedgerClient ledger,
            Duration probeTimeout) {
        this.store = store;
        this.transactions = transactions;
        this.ledger = ledger;
        this.probeTimeout = probeTimeout;
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
                .withDetail("halted", backlog.halted());
        backlog.oldestUnfinishedAcceptedAt().ifPresent(oldest -> health.withDetail("oldestUnfinishedAgeSeconds",
                Duration.between(oldest, Instant.now()).toSeconds()));
        return health.build();
    }

    /** TB 클라이언트에는 ping이 없어 이체 하나를 조회해 본다. 정해진 시간 안에 답하면 닿는다. */
    private boolean ledgerAnswers() {
        try {
            ledger.lookup(List.of(BigInteger.ONE)).get(probeTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
