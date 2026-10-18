package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.shop.Accounts;
import io.github.ledgerrelay.shop.Catalog;
import io.github.ledgerrelay.shop.Catalog.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pay, ship and cancel. Each one updates the order and submits a ledger command in one transaction. Submitting only
 * inserts a row into PG and never waits for TB; the worker sends the command to TB. So requests finish immediately even
 * when TB is unreachable.
 *
 * <p>Ship and cancel lock and update the order row first and then submit. The outcome handler takes locks in the same
 * order, so the two never deadlock (F33).
 */
@Service
public class OrderService {

    private final Orders orders;
    private final LedgerRelay relay;

    public OrderService(Orders orders, LedgerRelay relay) {
        this.orders = orders;
        this.relay = relay;
    }

    /**
     * Pay = reserve (authorize). A repeated request with the same idempotency key returns the original order and
     * creates neither a new order nor a new command.
     */
    @Transactional
    public Checkout checkout(String idempotencyKey, long buyerId, String productCode) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("The Idempotency-Key header is required");
        }
        Product product = Catalog.find(productCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product: " + productCode));
        if (!Accounts.isBuyer(buyerId)) {
            throw new IllegalArgumentException("Unknown buyer: " + buyerId);
        }
        var inserted = orders.insertIfAbsent(idempotencyKey, buyerId, product.code(), product.priceSatang());
        if (inserted.isEmpty()) {
            ShopOrder existing = orders.findByKey(idempotencyKey).orElseThrow();
            if (existing.buyerId() != buyerId || !existing.productCode().equals(product.code())) {
                throw new IdempotencyMismatchException(idempotencyKey);
            }
            return new Checkout(existing, false);
        }
        ShopOrder order = inserted.get();
        String reserve = ShopOrder.commandId(order.id(), "reserve");
        orders.recordCommand(order.id(), reserve, CommandKind.RESERVE);
        relay.submit(LedgerCommand.reserve(reserve).orderingKey(order.orderingKey()).debit(buyerId)
                .credit(Accounts.MERCHANT).amount(order.amountSatang()).ledger(Accounts.LEDGER).code(Accounts.SALE_CODE)
                .userData64(order.id()).build());
        return new Checkout(order, true);
    }

    /** Ship = post (capture). Only an order whose reservation was applied (authorized) can be shipped. */
    @Transactional
    public ShopOrder ship(long orderId) {
        ShopOrder order = orders.lock(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.state() != OrderState.AUTHORIZED) {
            throw new OrderConflictException("Only an authorized order can be shipped. Current state: " + order.state().label());
        }
        orders.changeState(orderId, OrderState.SHIP_REQUESTED, null);
        String post = ShopOrder.commandId(orderId, "post");
        orders.recordCommand(orderId, post, CommandKind.POST);
        // Without an amount, the full reserved amount is posted
        relay.submit(LedgerCommand.post(post).orderingKey(order.orderingKey())
                .reservation(ShopOrder.commandId(orderId, "reserve")).build());
        return orders.find(orderId).orElseThrow();
    }

    /**
     * Cancel = void. Allowed until a post is accepted, even if the reservation has not been applied yet. The void has a
     * later seq than the reservation on the same key, so it goes out after the reservation finishes. Rejected once
     * shipping was requested (refunds are a non-goal).
     */
    @Transactional
    public ShopOrder cancel(long orderId) {
        ShopOrder order = orders.lock(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.state() != OrderState.ACCEPTED && order.state() != OrderState.AUTHORIZED) {
            throw new OrderConflictException("Only an order that has not shipped can be cancelled. Current state: " + order.state().label());
        }
        release(order);
        return orders.find(orderId).orElseThrow();
    }

    /** Changes the order to cancelling and submits a void. The caller has already locked the order row. */
    void release(ShopOrder lockedOrder) {
        orders.changeState(lockedOrder.id(), OrderState.CANCEL_REQUESTED, null);
        String voidId = ShopOrder.commandId(lockedOrder.id(), "void");
        orders.recordCommand(lockedOrder.id(), voidId, CommandKind.VOID);
        relay.submit(LedgerCommand.voidReservation(voidId).orderingKey(lockedOrder.orderingKey())
                .reservation(reservationOf(lockedOrder)).build());
    }

    /**
     * The reservation command of the order. Normally order-&lt;id&gt;-reserve; orders the demo writes directly follow
     * the same rule.
     */
    private String reservationOf(ShopOrder order) {
        return orders.commandsOf(order.id()).stream().filter(command -> command.kind() == CommandKind.RESERVE)
                .map(OrderCommand::commandId).findFirst()
                .orElseThrow(() -> new IllegalStateException("Order " + order.id() + " has no reservation command"));
    }
}
