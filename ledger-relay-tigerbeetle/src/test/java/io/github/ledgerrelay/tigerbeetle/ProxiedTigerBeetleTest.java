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

@DisplayName("TigerBeetle behind Toxiproxy")
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
        payer = ledger.openAccount("buyer");
        payee = ledger.openAccount("merchant");
    }

    @AfterEach
    void restoreAndDisconnect() throws Exception {
        tb.restoreConnection();
        ledger.close();
    }

    @Test
    @DisplayName("With a healthy connection, a transfer through the proxy is newly recorded")
    void transferThroughProxy() {
        // when
        CreateTransferStatus result = ledger.submit(transfer(7).from(payer).to(payee));

        // then
        assertCreated(result);
    }

    @Nested
    @Order(1)
    @DisplayName("A transfer sent while the connection is cut (the TB client has no timeout)")
    class SentWhileDisconnected {

        PendingTransfer pending;

        @BeforeEach
        void givenATransferSentWhileDisconnected() throws Exception {
            tb.cutConnection();
            pending = ledger.submitInBackground(transfer(42).from(payer).to(payee));
        }

        @Test
        @DisplayName("Waits without an error, and is recorded exactly once after the connection is restored")
        void waitsThenIsRecordedOnceAfterRestore() throws Exception {
            // then: while cut, there is neither a reply nor an error
            assertStillWaitingAfter(pending, Duration.ofSeconds(3));

            // when: the connection is restored
            tb.restoreConnection();

            // then
            assertCreated(pending.awaitResult(Duration.ofSeconds(60)));
            assertMovedExactly(ledger, payer, payee, 42);
        }
    }
}
