package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.shop.order.OrderCommand;
import io.github.ledgerrelay.shop.order.OrderConflictException;
import io.github.ledgerrelay.shop.order.OrderState;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Effects of the sample shop that several test files check. Each method checks one effect. */
public final class ShopAssertions {

    private ShopAssertions() {
    }

    /** Exactly {@code amount} more of the buyer's money is held than {@code heldBefore}. */
    public static void assertNewlyHeld(ShopProbe probe, long buyer, long amount, long heldBefore) {
        assertThat(probe.heldBy(buyer) - heldBefore).as("money of buyer %d newly held", buyer).isEqualTo(amount);
    }

    /** The money held from the buyer is back to {@code heldBefore}: the hold was posted or released. */
    public static void assertHoldReleased(ShopProbe probe, long buyer, long heldBefore) {
        assertThat(probe.heldBy(buyer)).as("money of buyer %d held", buyer).isEqualTo(heldBefore);
    }

    /** The merchant received exactly {@code amount} more than {@code receivedBefore}. */
    public static void assertMerchantReceived(ShopProbe probe, long amount, long receivedBefore) {
        assertThat(probe.merchantReceived() - receivedBefore).as("money newly received by the merchant").isEqualTo(amount);
    }

    /** The order has exactly these ledger commands, in order. */
    public static void assertCommands(Orders orders, long orderId, CommandKind... kinds) {
        assertThat(orders.commandsOf(orderId)).as("commands of order %d", orderId).extracting(OrderCommand::kind)
                .containsExactly(kinds);
    }

    /** The order is still in {@code state}. */
    public static void assertStillIn(Orders orders, long orderId, OrderState state) {
        assertThat(orders.find(orderId).orElseThrow().state()).as("state of order %d", orderId).isEqualTo(state);
    }

    /** The order ended with this failure reason. */
    public static void assertFailedFor(ShopOrder order, String reason) {
        assertThat(order.failureReason()).as("failure reason of order %d", order.id()).isEqualTo(reason);
    }

    /** The order ended without a failure reason. */
    public static void assertNoFailure(ShopOrder order) {
        assertThat(order.failureReason()).as("failure reason of order %d", order.id()).isNull();
    }

    /** The request is refused because the order is in the wrong state. */
    public static void assertRefusedForState(ThrowingCallable attempt) {
        assertThatThrownBy(attempt).isInstanceOf(OrderConflictException.class);
    }

    /** The HTTP request was answered with this status. */
    public static void assertAnswered(MvcTestResult result, HttpStatus status) {
        assertThat(result).hasStatus(status);
    }
}
