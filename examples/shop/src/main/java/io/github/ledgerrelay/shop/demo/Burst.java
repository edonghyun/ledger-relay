package io.github.ledgerrelay.shop.demo;

import io.github.ledgerrelay.shop.Accounts;
import io.github.ledgerrelay.shop.Catalog;
import io.github.ledgerrelay.shop.order.OrderService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Places orders from several buyers at once, each on its own ordering key. The scenario shows that two workers split
 * the work and never claim the same command together. Contention on one key is covered by the double-click scenario.
 */
@Component
@Profile("demo")
class Burst {

    static final int MAX = 200;
    /** Leaves out the last buyer (1005), which the balance-draining test uses. */
    private static final List<Long> BUYERS = Accounts.BUYERS.subList(0, 4);

    private final OrderService shop;

    Burst(OrderService shop) {
        this.shop = shop;
    }

    List<Long> run(int count) {
        if (count < 1 || count > MAX) {
            throw new IllegalArgumentException("A burst must have 1 to " + MAX + " orders: " + count);
        }
        List<Catalog.Product> products = Catalog.all();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Long>> orders = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long buyer = BUYERS.get(i % BUYERS.size());
                String product = products.get(i % products.size()).code();
                orders.add(CompletableFuture.supplyAsync(
                        () -> shop.checkout("burst-" + UUID.randomUUID(), buyer, product).order().id(), pool));
            }
            return orders.stream().map(CompletableFuture::join).toList();
        }
    }
}
