package io.github.ledgerrelay.shop.demo;

import io.github.ledgerrelay.shop.order.OrderView;
import io.github.ledgerrelay.shop.order.Orders;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Scenarios tab of the UI. Exists only in the demo profile. A production app does not expose paths like these. */
@RestController
@RequestMapping("/api/demo")
@Profile("demo")
class DemoController {

    private final LedgerLink link;
    private final BadPostInjector injector;
    private final Burst burst;
    private final Orders orders;

    DemoController(LedgerLink link, BadPostInjector injector, Burst burst, Orders orders) {
        this.link = link;
        this.injector = injector;
        this.burst = burst;
        this.orders = orders;
    }

    @GetMapping("/ledger")
    Map<String, Boolean> ledger() {
        return Map.of("connected", link.connected());
    }

    @PostMapping("/ledger/cut")
    Map<String, Boolean> cut() {
        link.connect(false);
        return ledger();
    }

    @PostMapping("/ledger/restore")
    Map<String, Boolean> restore() {
        link.connect(true);
        return ledger();
    }

    @PostMapping("/bad-post")
    OrderView badPost() {
        long orderId = injector.inject();
        return OrderView.of(orders.find(orderId).orElseThrow(), orders.isHalted(orderId));
    }

    @PostMapping("/burst")
    Map<String, List<Long>> burst(@RequestParam(defaultValue = "50") int count) {
        return Map.of("orderIds", burst.run(count));
    }
}
