package io.github.ledgerrelay.shop.demo;

import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import io.github.ledgerrelay.shop.Accounts;
import io.github.ledgerrelay.shop.Catalog;
import io.github.ledgerrelay.shop.order.ShopOrder;
import java.math.BigInteger;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the "bad post" of scenario 3 directly into the library tables. <b>The normal API cannot create these rows.
 * Demo only.</b> The library's acceptance checks reject a post with an earlier sequence number than its reservation
 * (F9). To imitate rows created by a bug or by a direct DB edit, the rows bypass those checks. The schema CHECK
 * constraints still hold.
 *
 * <p>On the key of one new order it writes a post at seq 1 (referencing the reservation at seq 2) and a reservation at
 * seq 2. The post is the head. The worker's gate halts that post as "reservation not earlier", and only that key is
 * blocked. When the operator abandons it, the outcome handler accepts a void and the order ends as cancelled.
 */
@Component
@Profile("demo")
class BadPostInjector {

    /** Buyer and product of this order. The scenario does not let the UI choose them. */
    static final long BUYER = 1003;
    static final String PRODUCT = "mouse";

    private static final String INSERT_COMMAND = "INSERT INTO ledger_command (command_id, transfer_id, ordering_key, "
            + "seq, kind, reservation_command_id, pending_transfer_id, debit_account_id, credit_account_id, amount, "
            + "ledger, code, timeout_seconds, user_data_64, user_data_32, content_hash, state, is_head) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 'demo-injected', 'PENDING', ?)";

    private final JdbcClient sql;
    private final TransferIdGenerator transferIds;

    BadPostInjector(DataSource dataSource, TransferIdGenerator transferIds) {
        this.sql = JdbcClient.create(dataSource);
        this.transferIds = transferIds;
    }

    /** Creates an order and writes the two bad rows. Returns the id of the new order. */
    @Transactional
    long inject() {
        long amount = Catalog.find(PRODUCT).orElseThrow().priceSatang();
        long orderId = sql.sql("INSERT INTO shop_order (idempotency_key, buyer_id, product_code, amount_satang, state) "
                        + "VALUES (?, ?, ?, ?, 'ACCEPTED') RETURNING id")
                .params("demo-bad-post-" + UUID.randomUUID(), BUYER, PRODUCT, amount).query(Long.class).single();
        String key = ShopOrder.orderingKey(orderId);
        String post = ShopOrder.commandId(orderId, "post");
        String reserve = ShopOrder.commandId(orderId, "reserve");
        BigInteger postTransfer = transferIds.next();
        BigInteger reserveTransfer = transferIds.next();
        sql.sql("INSERT INTO shop_order_command (command_id, order_id, kind) VALUES (?, ?, 'POST'), (?, ?, 'RESERVE')")
                .params(post, orderId, reserve, orderId).update();
        sql.sql("INSERT INTO ledger_ordering_key (ordering_key, next_seq) VALUES (?, 3)").param(key).update();
        // seq 1: full-amount post referencing the reservation at seq 2. This is the head
        sql.sql(INSERT_COMMAND).params(post, postTransfer, key, 1, "POST", reserve, reserveTransfer, BigInteger.ZERO,
                BigInteger.ZERO, LedgerCommand.AMOUNT_MAX, 0, 0, orderId, true).update();
        // seq 2: buyer → merchant reservation
        sql.sql(INSERT_COMMAND).params(reserve, reserveTransfer, key, 2, "RESERVE", null, null,
                BigInteger.valueOf(BUYER), BigInteger.valueOf(Accounts.MERCHANT), BigInteger.valueOf(amount),
                Accounts.LEDGER, Accounts.SALE_CODE, orderId, false).update();
        return orderId;
    }
}
