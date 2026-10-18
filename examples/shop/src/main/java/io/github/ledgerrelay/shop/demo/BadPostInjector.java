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
 * 장면 3의 "잘못된 확정"을 라이브러리 표에 직접 넣는다. <b>정상 API로는 만들 수 없는 행이다. 데모 전용이다.</b>
 * 라이브러리의 접수 검사는 예약보다 앞 순번의 확정을 거부한다(F9). 그래서 버그나 DB 직접 수정으로 생긴 행을 흉내 내려고 검사를
 * 거치지 않고 넣는다. 스키마 CHECK는 지킨다.
 *
 * <p>새 주문 하나의 키에 순번 1 확정(순번 2의 예약을 참조), 순번 2 예약을 넣는다. 확정이 맨 앞이다. 워커의 게이트가 그 확정을
 * "예약이 앞 순번이 아님"으로 멈추고, 그 키만 막힌다. 운영자가 포기하면 핸들러가 해제를 접수해 주문은 취소됨으로 끝난다.
 */
@Component
@Profile("demo")
class BadPostInjector {

    /** 이 주문의 구매자와 상품. 화면에서 고르지 않는 장면이다. */
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

    /** 주문을 만들고 잘못된 두 행을 넣는다. 만든 주문의 id. */
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
        // 순번 1: 순번 2의 예약을 참조하는 전액 확정. 맨 앞이다
        sql.sql(INSERT_COMMAND).params(post, postTransfer, key, 1, "POST", reserve, reserveTransfer, BigInteger.ZERO,
                BigInteger.ZERO, LedgerCommand.AMOUNT_MAX, 0, 0, orderId, true).update();
        // 순번 2: 구매자 → 가맹점 예약
        sql.sql(INSERT_COMMAND).params(reserve, reserveTransfer, key, 2, "RESERVE", null, null,
                BigInteger.valueOf(BUYER), BigInteger.valueOf(Accounts.MERCHANT), BigInteger.valueOf(amount),
                Accounts.LEDGER, Accounts.SALE_CODE, orderId, false).update();
        return orderId;
    }
}
