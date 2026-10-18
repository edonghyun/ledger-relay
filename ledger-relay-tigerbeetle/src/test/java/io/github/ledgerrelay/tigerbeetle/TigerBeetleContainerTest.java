package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertCreated;
import static io.github.ledgerrelay.tigerbeetle.testing.LedgerAssertions.assertMovedExactly;
import static io.github.ledgerrelay.tigerbeetle.testing.Transfer.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TigerBeetle test container")
class TigerBeetleContainerTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    static TigerBeetleTestLedger ledger;

    @BeforeAll
    static void start() {
        TB.start();
        ledger = new TigerBeetleTestLedger(TB.newClient());
    }

    @AfterAll
    static void stop() {
        if (ledger != null) {
            ledger.close();
        }
        TB.stop();
    }

    @BeforeEach
    void serverIsAlive() {
        TB.assertAlive();
    }

    @Test
    @DisplayName("One transfer sent to a real TB is newly recorded, and exactly that amount moves")
    void recordsOneTransfer() {
        // given
        Account payer = ledger.openAccount("buyer");
        Account payee = ledger.openAccount("merchant");

        // when
        CreateTransferStatus result = ledger.submit(transfer(10_000).from(payer).to(payee));

        // then
        assertCreated(result);
        assertMovedExactly(ledger, payer, payee, 10_000);
    }

    @Test
    @DisplayName("The client address is IPv4:port, not a host name")
    void replicaAddressIsIpv4AndPort() {
        assertThat(TB.replicaAddress()).matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d+");
    }

    @Test
    @DisplayName("The image tag equals the version of the tigerbeetle-java jar on the classpath — catches diverging version keys")
    void imageTagMatchesClientJarVersion() throws Exception {
        assertThat(TB.getDockerImageName()).isEqualTo("ghcr.io/tigerbeetle/tigerbeetle:" + clientJarVersion());
    }

    private static String clientJarVersion() throws Exception {
        Properties pom = new Properties();
        try (InputStream in = Client.class.getResourceAsStream(
                "/META-INF/maven/com.tigerbeetle/tigerbeetle-java/pom.properties")) {
            assertThat(in).as("pom.properties of the tigerbeetle-java jar").isNotNull();
            pom.load(in);
        }
        return pom.getProperty("version");
    }
}
