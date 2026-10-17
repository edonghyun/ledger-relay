package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertStillWaitingAfter;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;

import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.PendingTransfer;
import io.github.ledgerrelay.tigerbeetle.testing.ProxiedTigerBeetle;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Toxiproxy 뒤의 TigerBeetle — 스펙 §1.2·§8.2")
class ProxiedTigerBeetleTest {

    static ProxiedTigerBeetle tb;
    TigerBeetleTestLedger ledger;
    Account payer;
    Account payee;

    @BeforeAll
    static void start() {
        tb = ProxiedTigerBeetle.start();
    }

    @AfterAll
    static void stop() {
        if (tb != null) {
            tb.close();
        }
    }

    @BeforeEach
    void givenTwoAccountsBehindTheProxy() {
        tb.assertAlive();
        ledger = new TigerBeetleTestLedger(tb.newClient());
        payer = ledger.openAccount("구매자");
        payee = ledger.openAccount("가맹점");
    }

    @AfterEach
    void restoreAndDisconnect() throws Exception {
        tb.restoreConnection();
        ledger.close();
    }

    @Test
    @DisplayName("연결이 정상이면 프록시를 거친 이체도 새로 기록된다")
    void transferThroughProxy() {
        // when
        CreateTransferStatus result = ledger.submit(transfer(7).from(payer).to(payee));

        // then
        assertCreated(result);
    }

    @Nested
    @Order(1)
    @DisplayName("연결이 끊긴 동안 보낸 이체는 (TB 클라이언트에는 timeout이 없다)")
    class SentWhileDisconnected {

        PendingTransfer pending;

        @BeforeEach
        void givenATransferSentWhileDisconnected() throws Exception {
            tb.cutConnection();
            pending = ledger.submitInBackground(transfer(42).from(payer).to(payee));
        }

        @Test
        @DisplayName("오류 없이 대기하다가, 연결이 복구되면 정확히 한 번 기록된다")
        void waitsThenIsRecordedOnceAfterRestore() throws Exception {
            // then: 끊긴 동안에는 응답도 오류도 없다
            assertStillWaitingAfter(pending, Duration.ofSeconds(3));

            // when: 연결을 복구하면
            tb.restoreConnection();

            // then
            assertCreated(pending.awaitResult(Duration.ofSeconds(60)));
            assertMovedExactly(ledger, payer, payee, 42);
        }
    }
}
