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

@DisplayName("TigerBeetle 테스트 컨테이너")
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
    @DisplayName("실제 TB에 이체 1건을 보내면 새로 기록되고, 그 금액이 정확히 움직인다")
    void recordsOneTransfer() {
        // given
        Account payer = ledger.openAccount("구매자");
        Account payee = ledger.openAccount("가맹점");

        // when
        CreateTransferStatus result = ledger.submit(transfer(10_000).from(payer).to(payee));

        // then
        assertCreated(result);
        assertMovedExactly(ledger, payer, payee, 10_000);
    }

    @Test
    @DisplayName("클라이언트 주소는 호스트명이 아니라 IPv4:포트다")
    void replicaAddressIsIpv4AndPort() {
        assertThat(TB.replicaAddress()).matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d+");
    }

    @Test
    @DisplayName("이미지 태그는 클래스패스에 실린 tigerbeetle-java jar의 버전과 같다 — 버전 키가 갈라져도 잡아냄")
    void imageTagMatchesClientJarVersion() throws Exception {
        assertThat(TB.getDockerImageName()).isEqualTo("ghcr.io/tigerbeetle/tigerbeetle:" + clientJarVersion());
    }

    private static String clientJarVersion() throws Exception {
        Properties pom = new Properties();
        try (InputStream in = Client.class.getResourceAsStream(
                "/META-INF/maven/com.tigerbeetle/tigerbeetle-java/pom.properties")) {
            assertThat(in).as("tigerbeetle-java jar의 pom.properties").isNotNull();
            pom.load(in);
        }
        return pom.getProperty("version");
    }
}
