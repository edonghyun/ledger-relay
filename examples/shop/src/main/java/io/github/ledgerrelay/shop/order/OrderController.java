package io.github.ledgerrelay.shop.order;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    record CheckoutRequest(long buyerId, String product) {
    }

    private final OrderService shop;
    private final Orders orders;

    OrderController(OrderService shop, Orders orders) {
        this.shop = shop;
        this.orders = orders;
    }

    /** 새 주문이면 201, 같은 멱등 키로 다시 온 요청이면 200과 처음 주문. */
    @PostMapping
    ResponseEntity<OrderView> checkout(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CheckoutRequest request) {
        Checkout checkout = shop.checkout(idempotencyKey, request.buyerId(), request.product());
        return ResponseEntity.status(checkout.created() ? HttpStatus.CREATED : HttpStatus.OK).body(view(checkout.order()));
    }

    @PostMapping("/{id}/ship")
    OrderView ship(@PathVariable long id) {
        return view(shop.ship(id));
    }

    @PostMapping("/{id}/cancel")
    OrderView cancel(@PathVariable long id) {
        return view(shop.cancel(id));
    }

    @GetMapping
    List<OrderView> recent(@RequestParam(defaultValue = "50") int limit) {
        return orders.recent(Math.clamp(limit, 1, 200)).stream().map(this::view).toList();
    }

    private OrderView view(ShopOrder order) {
        return OrderView.of(order, orders.isHalted(order.id()));
    }
}
