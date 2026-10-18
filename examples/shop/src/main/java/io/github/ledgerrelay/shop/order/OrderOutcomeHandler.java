package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Applies the result of a ledger command to its order. The library calls it inside the transaction that writes the
 * command result. If this method throws, writing the result rolls back too, and the library calls it again after a
 * backoff.
 *
 * <p>It locks and updates the order row first and only then accepts a new command (F33). Payment, ship and cancel
 * requests also take the order row first, so the lock order is the same.
 *
 * <table>
 *   <caption>Command result → order</caption>
 *   <tr><th>Command</th><th>Result</th><th>Order</th></tr>
 *   <tr><td>reserve</td><td>applied</td><td>accepted → authorized. Unchanged if cancelling (a void follows)</td></tr>
 *   <tr><td>reserve</td><td>rejected, conflict</td><td>accepted → failed (with reason). cancelling → cancelled
 *   (no money is held)</td></tr>
 *   <tr><td>post</td><td>applied</td><td>shipping → paid</td></tr>
 *   <tr><td>post</td><td>rejected, conflict, skipped</td><td>if the order still holds the reservation, change it to
 *   cancelling and accept a void</td></tr>
 *   <tr><td>void</td><td>applied, skipped</td><td>cancelled</td></tr>
 *   <tr><td>void</td><td>rejected, conflict</td><td>failed (with reason). An operator has to look at it</td></tr>
 * </table>
 */
@Component
class OrderOutcomeHandler implements LedgerOutcomeHandler {

    private static final Log log = LogFactory.getLog(OrderOutcomeHandler.class);

    private final Orders orders;
    /** The submit port is built with this handler, so the service is looked up lazily. */
    private final ObjectProvider<OrderService> service;

    OrderOutcomeHandler(Orders orders, ObjectProvider<OrderService> service) {
        this.orders = orders;
        this.service = service;
    }

    @Override
    public void onCompleted(CommandStatus outcome) {
        Optional<OrderCommand> command = orders.commandOf(outcome.commandId());
        if (command.isEmpty()) {
            log.warn("Ignoring the result of a command this app did not submit: " + outcome.commandId());
            return;
        }
        long orderId = command.get().orderId();
        ShopOrder order = orders.lock(orderId).orElseThrow();
        switch (command.get().kind()) {
            case RESERVE -> onReservation(order, outcome);
            case POST -> onCapture(order, outcome);
            case VOID -> onRelease(order, outcome);
            case TRANSFER -> throw new IllegalStateException("Orders never submit plain transfers: " + outcome.commandId());
        }
    }

    private void onReservation(ShopOrder order, CommandStatus outcome) {
        boolean cancelling = order.state() == OrderState.CANCEL_REQUESTED;
        if (outcome.state() == State.APPLIED) {
            if (order.state() == OrderState.ACCEPTED) {
                orders.changeState(order.id(), OrderState.AUTHORIZED, null);
            }
        } else if (cancelling) {
            orders.changeState(order.id(), OrderState.CANCELLED, null);
        } else if (order.state() == OrderState.ACCEPTED) {
            orders.changeState(order.id(), OrderState.FAILED, reasonOf(outcome));
        }
    }

    private void onCapture(ShopOrder order, CommandStatus outcome) {
        if (outcome.state() == State.APPLIED) {
            orders.changeState(order.id(), OrderState.PAID, null);
        } else if (holdsReservation(order)) {
            // The post never went through. The held money must be released for the order to finish
            // (scenario 3: operator abandons → cancelled)
            service.getObject().release(order);
        }
    }

    private void onRelease(ShopOrder order, CommandStatus outcome) {
        if (outcome.state() == State.APPLIED || outcome.state() == State.SKIPPED) {
            // Skipped means the reservation was never applied. No money is held, so the cancellation is complete
            if (order.state() != OrderState.CANCELLED) {
                orders.changeState(order.id(), OrderState.CANCELLED, null);
            }
        } else {
            orders.changeState(order.id(), OrderState.FAILED, reasonOf(outcome));
        }
    }

    /** No void has been accepted yet and the order has not finished. */
    private boolean holdsReservation(ShopOrder order) {
        boolean released = orders.commandsOf(order.id()).stream().anyMatch(command -> command.kind() == CommandKind.VOID);
        return !released && order.state() != OrderState.FAILED && order.state() != OrderState.CANCELLED;
    }

    private static String reasonOf(CommandStatus outcome) {
        return outcome.rejection().map(rejection -> rejection.tbCode().wireName())
                .or(() -> outcome.skipReason().map(Enum::name)).orElse(outcome.state().name());
    }
}
