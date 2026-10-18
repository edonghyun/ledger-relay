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
 * 결제, 출고, 취소. 각각 한 트랜잭션에서 주문을 고치고 원장 명령을 접수한다. 접수는 PG에 행을 넣을 뿐 TB를 기다리지 않는다.
 * TB에는 워커가 보낸다. 그래서 TB가 끊겨도 요청은 바로 끝난다.
 *
 * <p>출고와 취소는 주문 행을 먼저 잠그고 고친 뒤 접수한다. 핸들러도 같은 순서로 잡으므로 둘이 교착하지 않는다(F33).
 */
@Service
public class OrderService {

    private final Orders orders;
    private final LedgerRelay relay;

    public OrderService(Orders orders, LedgerRelay relay) {
        this.orders = orders;
        this.relay = relay;
    }

    /** 결제 = 예약(승인). 같은 멱등 키로 다시 오면 처음 주문을 그대로 돌려준다. 새 주문도 새 명령도 만들지 않는다. */
    @Transactional
    public Checkout checkout(String idempotencyKey, long buyerId, String productCode) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key가 필요합니다");
        }
        Product product = Catalog.find(productCode)
                .orElseThrow(() -> new IllegalArgumentException("없는 상품입니다: " + productCode));
        if (!Accounts.isBuyer(buyerId)) {
            throw new IllegalArgumentException("없는 구매자입니다: " + buyerId);
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

    /** 출고 = 확정(매입). 예약이 적용되어 결제 승인된 주문만 출고한다. */
    @Transactional
    public ShopOrder ship(long orderId) {
        ShopOrder order = orders.lock(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.state() != OrderState.AUTHORIZED) {
            throw new OrderConflictException("결제 승인된 주문만 출고할 수 있습니다. 지금은 " + order.state().label() + "입니다");
        }
        orders.changeState(orderId, OrderState.SHIP_REQUESTED, null);
        String post = ShopOrder.commandId(orderId, "post");
        orders.recordCommand(orderId, post, CommandKind.POST);
        // 금액을 생략하면 예약 금액 전부를 확정한다
        relay.submit(LedgerCommand.post(post).orderingKey(order.orderingKey())
                .reservation(ShopOrder.commandId(orderId, "reserve")).build());
        return orders.find(orderId).orElseThrow();
    }

    /**
     * 취소 = 해제. 확정을 접수하기 전이면 된다. 예약이 아직 적용 전이어도 된다. 해제는 같은 키에서 예약 뒤 순번이라 예약이 끝난 뒤에
     * 나간다. 출고를 요청한 뒤에는 거부한다(환불은 비목표).
     */
    @Transactional
    public ShopOrder cancel(long orderId) {
        ShopOrder order = orders.lock(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.state() != OrderState.ACCEPTED && order.state() != OrderState.AUTHORIZED) {
            throw new OrderConflictException("출고 전 주문만 취소할 수 있습니다. 지금은 " + order.state().label() + "입니다");
        }
        release(order);
        return orders.find(orderId).orElseThrow();
    }

    /** 주문을 취소 요청으로 고치고 해제를 접수한다. 부르는 쪽이 주문 행을 이미 잠갔다. */
    void release(ShopOrder lockedOrder) {
        orders.changeState(lockedOrder.id(), OrderState.CANCEL_REQUESTED, null);
        String voidId = ShopOrder.commandId(lockedOrder.id(), "void");
        orders.recordCommand(lockedOrder.id(), voidId, CommandKind.VOID);
        relay.submit(LedgerCommand.voidReservation(voidId).orderingKey(lockedOrder.orderingKey())
                .reservation(reservationOf(lockedOrder)).build());
    }

    /** 주문의 예약 명령. 보통은 order-<id>-reserve이고, 데모가 직접 넣은 주문도 같은 규칙을 따른다. */
    private String reservationOf(ShopOrder order) {
        return orders.commandsOf(order.id()).stream().filter(command -> command.kind() == CommandKind.RESERVE)
                .map(OrderCommand::commandId).findFirst()
                .orElseThrow(() -> new IllegalStateException("주문 " + order.id() + "에 예약 명령이 없습니다"));
    }
}
