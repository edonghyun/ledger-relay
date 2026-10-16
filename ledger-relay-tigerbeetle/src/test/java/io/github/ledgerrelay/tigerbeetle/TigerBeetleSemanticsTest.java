package io.github.ledgerrelay.tigerbeetle;

import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.createAccounts;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.lookupAccount;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.singleStatus;
import static io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestData.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TB 0.17.9의 결과 의미 특성화. 근거: docs.tigerbeetle.com/reference/requests/create_transfers
 * (exists, exists_with_different_*, id_already_failed, transient errors).
 */
class TigerBeetleSemanticsTest {

    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    static Client client;

    @BeforeAll
    static void start() {
        TB.start();
        client = TB.newClient();
    }

    @AfterAll
    static void stop() throws Exception {
        client.close();
        TB.stop();
    }

    @BeforeEach
    void serverIsAlive() {
        TB.assertAlive();
    }

    @Test
    @DisplayName("§4.4 적용됨: 같은 ID·같은 본문을 다시 보내면 Exists이고 잔액은 한 번만 반영된다")
    void identicalResubmissionIsExists() throws Exception {
        byte[][] a = createAccounts(client, AccountFlags.NONE, AccountFlags.NONE);
        byte[] id = UInt128.id();

        assertThat(singleStatus(client.createTransfers(transfer(id, a[0], a[1], 500))))
                .isEqualTo(CreateTransferStatus.Created);
        assertThat(singleStatus(client.createTransfers(transfer(id, a[0], a[1], 500))))
                .isEqualTo(CreateTransferStatus.Exists);
        assertThat(lookupAccount(client, a[0]).getDebitsPosted()).isEqualTo(BigInteger.valueOf(500));
    }

    @Test
    @DisplayName("§4.4 충돌·G7: 같은 ID에 금액만 다르게 보내면 ExistsWithDifferentAmount이고 성공으로 볼 수 없다")
    void sameIdDifferentAmountIsConflict() throws Exception {
        byte[][] a = createAccounts(client, AccountFlags.NONE, AccountFlags.NONE);
        byte[] id = UInt128.id();

        assertThat(singleStatus(client.createTransfers(transfer(id, a[0], a[1], 500))))
                .isEqualTo(CreateTransferStatus.Created);

        assertThat(singleStatus(client.createTransfers(transfer(id, a[0], a[1], 501))))
                .isEqualTo(CreateTransferStatus.ExistsWithDifferentAmount);
        assertThat(lookupAccount(client, a[0]).getDebitsPosted()).isEqualTo(BigInteger.valueOf(500));
    }

    @Test
    @DisplayName("§4.4 이전 실패: 잔액 부족(ExceedsCredits)으로 실패한 ID는 다시 보내도 IdAlreadyFailed다")
    void transientFailureIsStickyToTheId() throws Exception {
        byte[][] a = createAccounts(client, AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS, AccountFlags.NONE);
        byte[] id = UInt128.id();

        assertThat(singleStatus(client.createTransfers(transfer(id, a[0], a[1], 100))))
                .isEqualTo(CreateTransferStatus.ExceedsCredits);
        assertThat(singleStatus(client.createTransfers(transfer(id, a[0], a[1], 100))))
                .isEqualTo(CreateTransferStatus.IdAlreadyFailed);
    }

    @Test
    @DisplayName("§2 명령=이체 ID 1개(D7): 실패한 ID는 충전 뒤에도 IdAlreadyFailed, 새 ID는 그때 상태로 판정되어 Created")
    void onlyANewIdSeesTheNewState() throws Exception {
        byte[][] a = createAccounts(client,
                AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS, AccountFlags.NONE, AccountFlags.NONE);
        byte[] constrained = a[0];
        byte[] merchant = a[1];
        byte[] funding = a[2];
        byte[] firstTry = UInt128.id();

        assertThat(singleStatus(client.createTransfers(transfer(firstTry, constrained, merchant, 100))))
                .isEqualTo(CreateTransferStatus.ExceedsCredits);

        assertThat(singleStatus(client.createTransfers(transfer(UInt128.id(), funding, constrained, 100))))
                .isEqualTo(CreateTransferStatus.Created);

        assertThat(singleStatus(client.createTransfers(transfer(firstTry, constrained, merchant, 100))))
                .isEqualTo(CreateTransferStatus.IdAlreadyFailed);
        assertThat(singleStatus(client.createTransfers(transfer(UInt128.id(), constrained, merchant, 100))))
                .isEqualTo(CreateTransferStatus.Created);
    }

    @Test
    @DisplayName("§4.4 잘못된 요청(P4): 없는 계좌로 보내면 DebitAccountNotFound다")
    void unknownAccountIsReported() throws Exception {
        byte[][] a = createAccounts(client, AccountFlags.NONE);

        assertThat(singleStatus(client.createTransfers(transfer(UInt128.id(), UInt128.id(), a[0], 1))))
                .isEqualTo(CreateTransferStatus.DebitAccountNotFound);
    }
}
