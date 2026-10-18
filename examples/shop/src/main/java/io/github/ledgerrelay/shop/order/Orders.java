package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 주문 표와 주문이 접수한 명령 표. 업무 판단은 하지 않는다({@link OrderService}, {@link OrderOutcomeHandler}가 한다). */
@Repository
public class Orders {

    private static final String COLUMNS = "id, idempotency_key, buyer_id, product_code, amount_satang, state, failure_reason, "
            + "created_at";

    private final JdbcClient sql;

    public Orders(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * 주문을 넣는다. 같은 멱등 키의 주문이 있으면 넣지 않고 비어 있는 값을 돌려준다. 그 키로 커밋 중인 트랜잭션이 있으면 끝날 때까지
     * 유일 인덱스에서 기다린다. 그래서 동시에 온 같은 요청 중 하나만 넣는다(F38).
     */
    Optional<ShopOrder> insertIfAbsent(String idempotencyKey, long buyerId, String productCode, long amountSatang) {
        return sql.sql("INSERT INTO shop_order (idempotency_key, buyer_id, product_code, amount_satang, state) "
                        + "VALUES (?, ?, ?, ?, 'ACCEPTED') ON CONFLICT (idempotency_key) DO NOTHING RETURNING " + COLUMNS)
                .params(idempotencyKey, buyerId, productCode, amountSatang).query(Orders::order).optional();
    }

    public Optional<ShopOrder> find(long id) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order WHERE id = ?").param(id).query(Orders::order).optional();
    }

    public Optional<ShopOrder> findByKey(String idempotencyKey) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order WHERE idempotency_key = ?").param(idempotencyKey)
                .query(Orders::order).optional();
    }

    /** 주문 행을 잠그고 읽는다. 상태를 고치고 같은 키에 접수하기 전에 먼저 부른다(F33의 순서). */
    Optional<ShopOrder> lock(long id) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order WHERE id = ? FOR UPDATE").param(id).query(Orders::order)
                .optional();
    }

    /** 최신 주문부터. */
    public List<ShopOrder> recent(int limit) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order ORDER BY id DESC LIMIT ?").param(limit).query(Orders::order)
                .list();
    }

    void changeState(long id, OrderState state, String failureReason) {
        sql.sql("UPDATE shop_order SET state = ?, failure_reason = ?, updated_at = now() WHERE id = ?")
                .params(state.name(), failureReason, id).update();
    }

    void recordCommand(long orderId, String commandId, CommandKind kind) {
        sql.sql("INSERT INTO shop_order_command (command_id, order_id, kind) VALUES (?, ?, ?)")
                .params(commandId, orderId, kind.name()).update();
    }

    /** 이 명령을 접수한 주문. 이 앱이 만든 명령이 아니면 비어 있다. */
    Optional<OrderCommand> commandOf(String commandId) {
        return sql.sql("SELECT order_id, command_id, kind FROM shop_order_command WHERE command_id = ?").param(commandId)
                .query(Orders::command).optional();
    }

    /** 주문의 명령을 순번 순서로. */
    public List<OrderCommand> commandsOf(long orderId) {
        return sql.sql("SELECT oc.order_id, oc.command_id, oc.kind FROM shop_order_command oc "
                        + "JOIN ledger_command c ON c.command_id = oc.command_id WHERE oc.order_id = ? ORDER BY c.seq")
                .param(orderId).query(Orders::command).list();
    }

    /** 주문의 명령 중 멈춘(HALTED) 것이 있는가. 화면의 halted 칩. */
    public boolean isHalted(long orderId) {
        return sql.sql("SELECT EXISTS (SELECT 1 FROM shop_order_command oc JOIN ledger_command c ON c.command_id = "
                + "oc.command_id WHERE oc.order_id = ? AND c.state = 'HALTED')").param(orderId).query(Boolean.class).single();
    }

    private static ShopOrder order(ResultSet row, int rowNumber) throws SQLException {
        return new ShopOrder(row.getLong("id"), row.getString("idempotency_key"), row.getLong("buyer_id"),
                row.getString("product_code"), row.getLong("amount_satang"), OrderState.valueOf(row.getString("state")),
                row.getString("failure_reason"), row.getTimestamp("created_at").toInstant());
    }

    private static OrderCommand command(ResultSet row, int rowNumber) throws SQLException {
        return new OrderCommand(row.getLong("order_id"), row.getString("command_id"),
                CommandKind.valueOf(row.getString("kind")));
    }
}
