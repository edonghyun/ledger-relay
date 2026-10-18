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
 * 여러 구매자의 주문을 각기 다른 키로 한꺼번에 넣는다. 워커 둘이 같은 명령을 함께 집지 않고 나눠 처리하는 것을
 * 보이려는 장면이다. 같은 키 경쟁은 더블 클릭 장면이 맡는다.
 */
@Component
@Profile("demo")
class Burst {

    static final int MAX = 200;
    /** 잔액을 비우는 테스트가 쓰는 마지막 구매자(1005)는 빼고 돌린다. */
    private static final List<Long> BUYERS = Accounts.BUYERS.subList(0, 4);

    private final OrderService shop;

    Burst(OrderService shop) {
        this.shop = shop;
    }

    List<Long> run(int count) {
        if (count < 1 || count > MAX) {
            throw new IllegalArgumentException("몰아넣기는 1~" + MAX + "건입니다: " + count);
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
