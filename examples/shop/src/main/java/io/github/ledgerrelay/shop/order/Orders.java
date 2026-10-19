package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerRelay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The order table and the table of commands accepted for orders. It makes no business decisions
 * ({@link OrderService} and {@link OrderOutcomeHandler} do).
 */
@Repository
public class Orders {

    private static final String COLUMNS =
            "id, idempotency_key, buyer_id, product_code, amount_satang, state, failure_reason, created_at";

    private final JdbcClient sql;
    /** The submit port is built with the outcome handler, which uses this repository, so it is looked up lazily. */
    private final ObjectProvider<LedgerRelay> relays;

    public Orders(DataSource dataSource, ObjectProvider<LedgerRelay> relays) {
        this.sql = JdbcClient.create(dataSource);
        this.relays = relays;
    }

    /**
     * Inserts an order. If an order with the same idempotency key exists, inserts nothing and returns empty. If another
     * transaction with that key is committing, waits on the unique index until it finishes. So of identical concurrent
     * requests only one inserts (F38).
     */
    Optional<ShopOrder> insertIfAbsent(String idempotencyKey, long buyerId, String productCode, long amountSatang) {
        return sql.sql("""
                INSERT INTO shop_order (idempotency_key, buyer_id, product_code, amount_satang, state)
                VALUES (?, ?, ?, ?, 'ACCEPTED')
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING
                """ + COLUMNS)
                .params(idempotencyKey, buyerId, productCode, amountSatang).query(Orders::order).optional();
    }

    public Optional<ShopOrder> find(long id) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order WHERE id = ?").param(id).query(Orders::order).optional();
    }

    public Optional<ShopOrder> findByKey(String idempotencyKey) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order WHERE idempotency_key = ?").param(idempotencyKey)
                .query(Orders::order).optional();
    }

    /** Locks and reads the order row. Called before changing the state and submitting on the same key (F33). */
    Optional<ShopOrder> lock(long id) {
        return sql.sql("SELECT " + COLUMNS + " FROM shop_order WHERE id = ? FOR UPDATE").param(id).query(Orders::order)
                .optional();
    }

    /** Newest orders first. */
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

    /** The order that submitted this command. Empty if this app did not create the command. */
    public Optional<OrderCommand> commandOf(String commandId) {
        return sql.sql("SELECT order_id, command_id, kind FROM shop_order_command WHERE command_id = ?").param(commandId)
                .query(Orders::command).optional();
    }

    /**
     * Records the final state of a command with the library's acceptance and completion times. Called by the outcome
     * handler in the transaction that writes the result, so it commits or rolls back with it. The acceptance time
     * replaces the shop's own insert time: both times then come from the library's clock.
     */
    void recordOutcome(CommandStatus outcome) {
        sql.sql("""
                UPDATE shop_order_command SET final_state = ?, accepted_at = ?, completed_at = ?
                WHERE command_id = ?
                """).params(outcome.state().name(), timestamp(outcome.acceptedAt()),
                outcome.completedAt().map(Orders::timestamp).orElse(null), outcome.commandId()).update();
    }

    /** The commands of an order, in the order they were accepted (the same as their order on the key). */
    public List<OrderCommand> commandsOf(long orderId) {
        return sql.sql("SELECT order_id, command_id, kind FROM shop_order_command WHERE order_id = ? ORDER BY position")
                .param(orderId).query(Orders::command).list();
    }

    /**
     * Whether any command of the order is halted. Drives the halted chip in the UI. A halted command has not reached a
     * final state, so only the order's unfinished commands are asked about, through the public query.
     */
    public boolean isHalted(long orderId) {
        List<String> unfinished = sql.sql(
                        "SELECT command_id FROM shop_order_command WHERE order_id = ? AND final_state IS NULL")
                .param(orderId).query(String.class).list();
        LedgerRelay relay = relays.getObject();
        return unfinished.stream().map(relay::status).flatMap(Optional::stream)
                .anyMatch(status -> status.state() == CommandStatus.State.HALTED);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
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
