package io.github.ledgerrelay.shop.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.shop.Accounts;
import io.github.ledgerrelay.shop.ShopProbe;
import io.github.ledgerrelay.shop.ShopTestEnvironment;
import io.github.ledgerrelay.shop.order.OrderService;
import io.github.ledgerrelay.shop.order.OrderState;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Breaks consistency on purpose, so it runs on its own PG and TB, not shared with other tests. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Orders charged twice — the numbers expose a second post for one order")
class DoubleTransferTest {

    static final ShopTestEnvironment ENVIRONMENT = ShopTestEnvironment.isolated();

    @DynamicPropertySource
    static void isolatedEnvironment(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    static void stop() {
        ENVIRONMENT.close();
    }

    @Autowired
    MockMvcTester http;
    @Autowired
    OrderService shop;
    @Autowired
    Orders orders;
    @Autowired
    Client client;
    @Autowired
    DataSource dataSource;
    @Autowired
    LedgerRelay relay;

    @Test
    @DisplayName("A shipped order's post carries the order id in TB — the post sets no user_data and TB copies it from "
            + "the reservation, which is how orders charged twice are counted")
    void thePostCarriesTheOrderIdOfItsReservation() throws Exception {
        // given
        ShopProbe probe = new ShopProbe(orders, client, dataSource);
        ShopOrder order = shop.checkout("inherit-1", 1002, "mouse").order();
        probe.awaitState(order.id(), OrderState.AUTHORIZED);

        // when
        shop.ship(order.id());
        probe.awaitState(order.id(), OrderState.PAID);

        // then
        assertCarriesOrderId(ShopOrder.commandId(order.id(), "post"), order.id());
    }

    @Test
    @DisplayName("Posting a paid order once more directly in TB makes orders charged twice 1 and the consistency verdict broken")
    void aSecondCaptureForOneOrderIsCounted() throws Exception {
        // given
        ShopProbe probe = new ShopProbe(orders, client, dataSource);
        ShopOrder order = shop.checkout("double-1", 1001, "mouse").order();
        probe.awaitState(order.id(), OrderState.AUTHORIZED);
        shop.ship(order.id());
        probe.awaitState(order.id(), OrderState.PAID);

        // when: bypassing the library, reserve and post once more with the same order id (imitates a bug or a manual transfer)
        captureAgainBehindTheRelaysBack(order);

        // then
        long deadline = System.nanoTime() + ShopProbe.SETTLE.toNanos();
        String consistency;
        do {
            Thread.sleep(100);
            consistency = http.get().uri("/api/consistency").exchange().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
        } while (StatusApiTest.number(consistency, "$.doubleTransfers") == 0 && System.nanoTime() < deadline);
        assertOrdersChargedTwice(consistency, 1);
        assertVerdict(consistency, "broken");
    }

    // ── Effects checked only in this file ────────────────────────────────

    private void assertCarriesOrderId(String commandId, long orderId) throws InterruptedException {
        assertThat(userData64OfTransfer(commandId)).as("user_data_64 of %s in TB", commandId).isEqualTo(orderId);
    }

    private static void assertOrdersChargedTwice(String consistency, long count) {
        assertThat(StatusApiTest.number(consistency, "$.doubleTransfers")).as("orders charged twice").isEqualTo(count);
    }

    private static void assertVerdict(String consistency, String verdict) {
        assertThat((String) JsonPath.read(consistency, "$.verdict")).as("consistency verdict").isEqualTo(verdict);
    }

    // ── Helpers used only in this file ───────────────────────────────────

    private long userData64OfTransfer(String commandId) throws InterruptedException {
        IdBatch ids = new IdBatch(1);
        ids.add(UInt128.asBytes(relay.status(commandId).orElseThrow().transferId()));
        TransferBatch found = client.lookupTransfers(ids);
        assertThat(found.next()).as("transfer of " + commandId + " in TB").isTrue();
        return found.getUserData64();
    }

    private void captureAgainBehindTheRelaysBack(ShopOrder order) throws InterruptedException {
        byte[] pendingId = UInt128.id();
        TransferBatch transfers = new TransferBatch(2);
        transfers.add();
        transfers.setId(pendingId);
        transfers.setDebitAccountId(order.buyerId());
        transfers.setCreditAccountId(Accounts.MERCHANT);
        transfers.setAmount(order.amountSatang());
        transfers.setLedger(Accounts.LEDGER);
        transfers.setCode(Accounts.SALE_CODE);
        transfers.setUserData64(order.id());
        transfers.setFlags(TransferFlags.PENDING);
        transfers.add();
        transfers.setId(UInt128.id());
        transfers.setPendingId(pendingId);
        transfers.setAmount(order.amountSatang());
        transfers.setFlags(TransferFlags.POST_PENDING_TRANSFER);
        CreateTransferResultBatch results = client.createTransfers(transfers);
        while (results.next()) {
            if (results.getStatus() != CreateTransferStatus.Created) {
                throw new IllegalStateException("given setup failed: " + results.getStatus());
            }
        }
    }
}
