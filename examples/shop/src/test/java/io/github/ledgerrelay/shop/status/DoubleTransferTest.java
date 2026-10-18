package io.github.ledgerrelay.shop.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
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

/** 정합성을 일부러 깨므로 다른 테스트와 나누지 않는 PG·TB 위에서 돈다. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("중복 이체 — 한 주문에 확정이 두 번 들어오면 숫자가 그것을 드러낸다")
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

    @Test
    @DisplayName("결제 완료된 주문에 TB로 직접 확정을 한 번 더 넣으면 중복 이체가 1이 되고 정합성은 broken이다")
    void aSecondCaptureForOneOrderIsCounted() throws Exception {
        // given
        ShopProbe probe = new ShopProbe(orders, client, dataSource);
        ShopOrder order = shop.checkout("double-1", 1001, "mouse").order();
        probe.awaitState(order.id(), OrderState.AUTHORIZED);
        shop.ship(order.id());
        probe.awaitState(order.id(), OrderState.PAID);

        // when: 라이브러리를 거치지 않고 같은 주문 id로 예약과 확정을 한 번 더 넣는다(버그나 수동 이체를 흉내)
        captureAgainBehindTheRelaysBack(order);

        // then
        long deadline = System.nanoTime() + ShopProbe.SETTLE.toNanos();
        String consistency;
        do {
            Thread.sleep(100);
            consistency = http.get().uri("/api/consistency").exchange().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
        } while (StatusApiTest.number(consistency, "$.doubleTransfers") == 0 && System.nanoTime() < deadline);
        assertThat(StatusApiTest.number(consistency, "$.doubleTransfers")).as("중복 이체").isEqualTo(1);
        assertThat((String) JsonPath.read(consistency, "$.verdict")).as("정합성").isEqualTo("broken");
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
                throw new IllegalStateException("given 준비 실패: " + results.getStatus());
            }
        }
    }
}
