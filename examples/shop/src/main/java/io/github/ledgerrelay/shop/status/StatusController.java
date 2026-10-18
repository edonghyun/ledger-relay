package io.github.ledgerrelay.shop.status;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.shop.Accounts.AccountBalance;
import io.github.ledgerrelay.shop.order.OrderCommand;
import io.github.ledgerrelay.shop.order.OrderNotFoundException;
import io.github.ledgerrelay.shop.order.OrderView;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import io.github.ledgerrelay.spring.LedgerRelayHealthIndicator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 화면이 1초마다 부르는 숫자. 모두 PG나 TB의 실제 상태에서 온다(03b 반목표 "가짜 숫자 없음"). */
@RestController
class StatusController {

    record Tiles(long accepted, long completed, long backlog, Long p99Millis, Long doubleTransfers) {
    }

    record Pipeline(long pending, long claimed, long applied, long completed, long halted) {
    }

    record Status(boolean ledgerUp, List<InstanceHeartbeat.Instance> instances, Tiles tiles, Pipeline pipeline) {
    }

    record Consistency(String verdict, boolean stale, long checked, long matched, long mismatches, long doubleTransfers,
            long merchantPostedSatang, long paidOrdersSatang) {
    }

    record Step(String commandId, String kind, String state, String transferId, long submitCount, Instant acceptedAt,
            Instant completedAt) {
    }

    record BuyerBalance(long heldSatang, long paidSatang) {
    }

    record MerchantBalance(long pendingSatang, long postedSatang) {
    }

    record Timeline(OrderView order, List<Step> steps, BuyerBalance buyer, MerchantBalance merchant) {
    }

    private final CommandStats stats;
    private final InstanceHeartbeat heartbeat;
    private final LedgerSnapshot ledger;
    private final LedgerRelayHealthIndicator health;
    private final Orders orders;
    private final LedgerRelay relay;

    StatusController(CommandStats stats, InstanceHeartbeat heartbeat, LedgerSnapshot ledger,
            LedgerRelayHealthIndicator health, Orders orders, LedgerRelay relay) {
        this.stats = stats;
        this.heartbeat = heartbeat;
        this.ledger = ledger;
        this.health = health;
        this.orders = orders;
        this.relay = relay;
    }

    @GetMapping("/api/status")
    Status status() {
        CommandStats.Counts counts = stats.counts();
        Long doubleTransfers = ledger.latest().map(LedgerSnapshot.Snapshot::doubleTransfers).orElse(null);
        return new Status(ledgerUp(), heartbeat.alive(),
                new Tiles(counts.accepted(), counts.completed(), counts.backlog(), counts.p99Millis(), doubleTransfers),
                new Pipeline(counts.pending(), counts.claimed(), counts.applied(), counts.completed(), counts.halted()));
    }

    @GetMapping("/api/consistency")
    Consistency consistency() {
        return ledger.latest().map(s -> new Consistency(s.verdict(), ledger.isStale(s), s.checked(), s.matched(),
                        s.mismatches(), s.doubleTransfers(), s.merchantPostedSatang(), s.paidOrdersSatang()))
                .orElse(new Consistency("unknown", true, 0, 0, 0, 0, 0, 0));
    }

    @GetMapping("/api/orders/{id}/timeline")
    Timeline timeline(@PathVariable long id) {
        ShopOrder order = orders.find(id).orElseThrow(() -> new OrderNotFoundException(id));
        List<Step> steps = orders.commandsOf(id).stream().map(this::step).flatMap(Optional::stream).toList();
        Optional<LedgerSnapshot.Snapshot> snapshot = ledger.latest();
        BuyerBalance buyer = snapshot.map(s -> s.balances().get(order.buyerId()))
                .map(b -> new BuyerBalance(b.debitsPending(), b.debitsPosted())).orElse(null);
        MerchantBalance merchant = snapshot.map(s -> s.balances().get(io.github.ledgerrelay.shop.Accounts.MERCHANT))
                .map(StatusController::merchant).orElse(null);
        return new Timeline(OrderView.of(order, orders.isHalted(id)), steps, buyer, merchant);
    }

    private Optional<Step> step(OrderCommand command) {
        return relay.status(command.commandId()).map(status -> step(command, status));
    }

    private static Step step(OrderCommand command, CommandStatus status) {
        return new Step(command.commandId(), command.kind().name(), status.state().name(),
                "0x" + status.transferId().toString(16), status.submitCount(), status.acceptedAt(),
                status.completedAt().orElse(null));
    }

    private static MerchantBalance merchant(AccountBalance balance) {
        return new MerchantBalance(balance.creditsPending(), balance.creditsPosted());
    }

    /** 라이브러리 health의 TB 확인. 기다리지 않는다(F36). */
    private boolean ledgerUp() {
        Object ledgerDetail = health.health().getDetails().get("ledger");
        return "UP".equals(ledgerDetail);
    }
}
