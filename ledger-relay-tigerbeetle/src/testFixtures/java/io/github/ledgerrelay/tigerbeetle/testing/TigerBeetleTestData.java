package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;
import java.math.BigInteger;

/** 실제 TB를 다루는 테스트의 공통 데이터 헬퍼. 모든 계좌는 같은 ledger·code를 쓴다. */
public final class TigerBeetleTestData {

    public static final int LEDGER = 1;
    public static final int CODE = 1;

    private TigerBeetleTestData() {
    }

    /** 플래그 하나당 계좌 하나를 만들고 ID를 같은 순서로 돌려준다. Created가 아닌 결과가 있으면 실패한다. */
    public static byte[][] createAccounts(Client client, int... flagsPerAccount) throws InterruptedException {
        AccountBatch batch = new AccountBatch(flagsPerAccount.length);
        byte[][] ids = new byte[flagsPerAccount.length][];
        for (int i = 0; i < flagsPerAccount.length; i++) {
            ids[i] = UInt128.id();
            batch.add();
            batch.setId(ids[i]);
            batch.setLedger(LEDGER);
            batch.setCode(CODE);
            batch.setFlags(flagsPerAccount[i]);
        }
        CreateAccountResultBatch results = client.createAccounts(batch);
        while (results.next()) {
            if (results.getStatus() != CreateAccountStatus.Created) {
                throw new IllegalStateException(
                        "계좌 생성 실패: position=" + results.getPosition() + " status=" + results.getStatus());
            }
        }
        return ids;
    }

    public static TransferBatch transfer(byte[] id, byte[] debit, byte[] credit, long amount) {
        TransferBatch batch = new TransferBatch(1);
        batch.add();
        batch.setId(id);
        batch.setDebitAccountId(debit);
        batch.setCreditAccountId(credit);
        batch.setAmount(BigInteger.valueOf(amount));
        batch.setLedger(LEDGER);
        batch.setCode(CODE);
        return batch;
    }

    /** 이체 1건 요청의 결과 상태. 결과가 정확히 1개가 아니면 실패한다. */
    public static CreateTransferStatus singleStatus(CreateTransferResultBatch results) {
        if (!results.next()) {
            throw new IllegalStateException("결과가 비어 있습니다");
        }
        CreateTransferStatus status = results.getStatus();
        if (results.next()) {
            throw new IllegalStateException("결과가 2개 이상입니다");
        }
        return status;
    }

    /** 계좌 1개를 조회해 첫 행에 위치한 배치를 돌려준다. 없으면 실패한다. */
    public static AccountBatch lookupAccount(Client client, byte[] accountId) throws InterruptedException {
        IdBatch ids = new IdBatch(1);
        ids.add(accountId);
        AccountBatch accounts = client.lookupAccounts(ids);
        if (!accounts.next()) {
            throw new IllegalStateException("계좌가 없습니다");
        }
        return accounts;
    }
}
