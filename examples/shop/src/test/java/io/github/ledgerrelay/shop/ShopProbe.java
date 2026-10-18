package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.shop.order.OrderState;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Lets tests see how far an order has progressed and the actual balances in the ledger. */
public final class ShopProbe {

    /** Waits long enough to cover the retry backoff after TB is cut and restored. */
    public static final Duration SETTLE = Duration.ofSeconds(20);

    private final Orders orders;
    private final Client client;
    private final JdbcClient sql;

    public ShopProbe(Orders orders, Client client, DataSource dataSource) {
        this.orders = orders;
        this.client = client;
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * Waits until no command is pending or in flight. Tests that compare balances call it at the very start of given,
     * so that the "before" balance is not measured while commands left by an earlier test still change it. HALTED
     * commands are not counted, because they never finish until an operator resolves them.
     */
    public void awaitQuiet() throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            long busy = sql.sql("SELECT count(*) FROM ledger_command WHERE state IN ('PENDING','CLAIMED')")
                    .query(Long.class).single();
            if (busy == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Pending and in-flight commands did not drain within " + SETTLE);
    }

    public ShopOrder awaitState(long orderId, OrderState expected) throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        ShopOrder last = null;
        while (System.nanoTime() < deadline) {
            last = orders.find(orderId).orElseThrow();
            if (last.state() == expected) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Order " + orderId + " did not reach " + expected + " within " + SETTLE + ". Last state: " + last);
    }

    /** Amount of the buyer's money held by reservations (satang). */
    public long heldBy(long buyer) {
        return Accounts.balanceOf(client, buyer).debitsPending();
    }

    /** Amount the merchant received through posts (satang). */
    public long merchantReceived() {
        return Accounts.balanceOf(client, Accounts.MERCHANT).creditsPosted();
    }

    /** given setup: moves all of the buyer's available money back to the funding account. Later payments are rejected for insufficient funds. */
    public void drain(long buyer) throws InterruptedException {
        Accounts.AccountBalance balance = Accounts.balanceOf(client, buyer);
        long available = balance.creditsPosted() - balance.debitsPosted() - balance.debitsPending();
        if (available == 0) {
            return;
        }
        TransferBatch drain = new TransferBatch(1);
        drain.add();
        drain.setId(UInt128.id());
        drain.setDebitAccountId(buyer);
        drain.setCreditAccountId(Accounts.FUNDING);
        drain.setAmount(available);
        drain.setLedger(Accounts.LEDGER);
        drain.setCode(Accounts.TOP_UP_CODE);
        CreateTransferResultBatch result = client.createTransfers(drain);
        if (result.next() && result.getStatus() != CreateTransferStatus.Created) {
            throw new IllegalStateException("given setup failed: could not drain buyer " + buyer + " — " + result.getStatus());
        }
    }
}
