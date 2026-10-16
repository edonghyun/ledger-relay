package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.createAccounts;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.lookupAccount;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.singleStatus;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TigerBeetleContainerTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();

    @BeforeAll
    static void start() {
        TB.start();
    }

    @AfterAll
    static void stop() {
        TB.stop();
    }

    @BeforeEach
    void serverIsAlive() {
        TB.assertAlive();
    }

    @Test
    @DisplayName("§10 0단계: 실제 TB에 계좌 2개를 만들고 이체 1건을 기록하면 양쪽 잔액에 반영된다")
    void recordsOneTransfer() throws Exception {
        try (Client client = TB.newClient()) {
            byte[][] accounts = createAccounts(client, AccountFlags.NONE, AccountFlags.NONE);

            CreateTransferStatus status =
                    singleStatus(client.createTransfers(transfer(UInt128.id(), accounts[0], accounts[1], 10_000)));

            assertThat(status).isEqualTo(CreateTransferStatus.Created);
            AccountBatch debit = lookupAccount(client, accounts[0]);
            AccountBatch credit = lookupAccount(client, accounts[1]);
            assertThat(debit.getDebitsPosted()).isEqualTo(BigInteger.valueOf(10_000));
            assertThat(credit.getCreditsPosted()).isEqualTo(BigInteger.valueOf(10_000));
        }
    }

    @Test
    @DisplayName("§12 TB 클라이언트 주소: 픽스처는 호스트명이 아니라 IPv4:포트를 돌려준다")
    void replicaAddressIsIpv4AndPort() {
        assertThat(TB.replicaAddress()).matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d+");
    }

    @Test
    @DisplayName("§12 버전 고정: 이미지 태그는 클래스패스에 실린 tigerbeetle-java jar의 버전과 같다 — 버전 키가 갈라져도 잡아냄")
    void imageTagMatchesClientJarVersion() throws Exception {
        Properties pom = new Properties();
        try (InputStream in = Client.class.getResourceAsStream(
                "/META-INF/maven/com.tigerbeetle/tigerbeetle-java/pom.properties")) {
            assertThat(in).as("tigerbeetle-java jar의 pom.properties").isNotNull();
            pom.load(in);
        }

        assertThat(TB.getDockerImageName()).isEqualTo("ghcr.io/tigerbeetle/tigerbeetle:" + pom.getProperty("version"));
    }
}
