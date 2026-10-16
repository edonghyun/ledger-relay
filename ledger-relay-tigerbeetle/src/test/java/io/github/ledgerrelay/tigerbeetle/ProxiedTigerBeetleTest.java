package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.createAccounts;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.lookupAccount;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.singleStatus;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.tigerbeetle.testing.ProxiedTigerBeetle;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProxiedTigerBeetleTest {

    static ProxiedTigerBeetle tb;

    @BeforeAll
    static void start() {
        tb = ProxiedTigerBeetle.start();
    }

    @AfterAll
    static void stop() {
        tb.close();
    }

    @AfterEach
    void alwaysRestore() throws Exception {
        tb.restoreConnection();
    }

    @Test
    @DisplayName("§8.2 TB 실연동: Toxiproxy를 경유한 이체도 Created로 기록된다")
    void transferThroughProxy() throws Exception {
        try (Client client = tb.newClient()) {
            byte[][] a = createAccounts(client, AccountFlags.NONE, AccountFlags.NONE);

            assertThat(singleStatus(client.createTransfers(transfer(UInt128.id(), a[0], a[1], 7))))
                    .isEqualTo(CreateTransferStatus.Created);
        }
    }

    @Test
    @DisplayName("§1.2 TB 클라이언트는 timeout이 없다: 연결이 끊긴 동안 요청은 실패하지 않고 대기하며, 복구되면 같은 요청이 한 번만 기록된다")
    void requestWaitsWhileCutAndCompletesOnceAfterRestore() throws Exception {
        try (Client client = tb.newClient()) {
            byte[][] a = createAccounts(client, AccountFlags.NONE, AccountFlags.NONE);

            tb.cutConnection();
            CompletableFuture<CreateTransferResultBatch> pending =
                    client.createTransfersAsync(transfer(UInt128.id(), a[0], a[1], 42));

            Thread.sleep(3_000); // 결정적 관찰 창: 끊긴 동안에는 응답도 오류도 오지 않아야 한다
            assertThat(pending).isNotDone();

            tb.restoreConnection();
            assertThat(singleStatus(pending.get(60, TimeUnit.SECONDS))).isEqualTo(CreateTransferStatus.Created);
            assertThat(lookupAccount(client, a[0]).getDebitsPosted()).isEqualTo(BigInteger.valueOf(42));
        }
    }
}
