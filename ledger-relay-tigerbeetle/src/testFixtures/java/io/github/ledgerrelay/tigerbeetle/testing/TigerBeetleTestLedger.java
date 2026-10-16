package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import java.math.BigInteger;

/**
 * 테스트가 실제 TigerBeetle을 다루는 말. 계좌를 열고, 이체를 보내고, 잔액을 본다.
 *
 * <p>given 전용 메서드({@link #recorded}, {@link #rejected})는 전제가 성립하지 않으면 그 자리에서
 * "given 준비 실패"로 멈춘다. then 단계까지 가서 엉뚱한 이유로 실패하지 않게 하려는 것이다.
 */
public final class TigerBeetleTestLedger implements AutoCloseable {

    public static final int LEDGER = 1;
    public static final int CODE = 1;

    private final Client client;
    private Account depositSource;

    public TigerBeetleTestLedger(Client client) {
        this.client = client;
    }

    public Account openAccount(String name) {
        return open(Account.notOpened(name), AccountFlags.NONE);
    }

    /** 잔액보다 많이 출금할 수 없는 계좌(지갑). 새 계좌의 잔액은 0이다. */
    public Account openAccountThatCannotOverdraw(String name) {
        return open(Account.notOpened(name), AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
    }

    /** given: 아직 열지 않은 계좌({@link Account#notOpened})를 그 ID 그대로 연다. */
    public Account open(Account account) {
        return open(account, AccountFlags.NONE);
    }

    /** 테스트 바깥의 입금 계좌에서 {@code amount}를 넣어 준다. */
    public void deposit(Account to, long amount) {
        if (depositSource == null) {
            depositSource = openAccount("입금 계좌");
        }
        recorded(Transfer.transfer(amount).from(depositSource).to(to));
    }

    /** when: 이체를 보내고 결과 코드를 돌려준다. */
    public CreateTransferStatus submit(Transfer transfer) {
        try {
            return singleStatus(client.createTransfers(batchOf(transfer)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** when: 이체를 보내되 응답을 기다리지 않는다. */
    public PendingTransfer submitInBackground(Transfer transfer) {
        return new PendingTransfer(transfer, client.createTransfersAsync(batchOf(transfer)));
    }

    /** given: 이 이체가 이미 기록되어 있다. */
    public Transfer recorded(Transfer transfer) {
        CreateTransferStatus result = submit(transfer);
        if (result != CreateTransferStatus.Created) {
            throw new IllegalStateException(
                    "given 준비 실패: " + transfer + " 이체가 기록되어야 했지만 결과는 " + result);
        }
        return transfer;
    }

    /** given: 이 이체가 {@code reason}으로 거부된 적이 있다. */
    public Transfer rejected(Transfer transfer, CreateTransferStatus reason) {
        CreateTransferStatus result = submit(transfer);
        if (result != reason) {
            throw new IllegalStateException(
                    "given 준비 실패: " + transfer + " 이체가 " + reason + " 사유로 거부되어야 했지만 결과는 " + result);
        }
        return transfer;
    }

    public Balance balanceOf(Account account) {
        IdBatch ids = new IdBatch(1);
        ids.add(account.id());
        try {
            AccountBatch found = client.lookupAccounts(ids);
            if (!found.next()) {
                throw new IllegalStateException(account + " 계좌가 원장에 없습니다");
            }
            return new Balance(exact(found.getDebitsPosted()), exact(found.getCreditsPosted()),
                    exact(found.getDebitsPending()), exact(found.getCreditsPending()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    static CreateTransferStatus singleStatus(CreateTransferResultBatch results) {
        if (!results.next()) {
            throw new IllegalStateException("결과가 비어 있습니다");
        }
        CreateTransferStatus status = results.getStatus();
        if (results.next()) {
            throw new IllegalStateException("결과가 2개 이상입니다");
        }
        return status;
    }

    private Account open(Account account, int flags) {
        AccountBatch batch = new AccountBatch(1);
        batch.add();
        batch.setId(account.id());
        batch.setLedger(LEDGER);
        batch.setCode(CODE);
        batch.setFlags(flags);
        try {
            CreateAccountResultBatch results = client.createAccounts(batch);
            while (results.next()) {
                if (results.getStatus() != CreateAccountStatus.Created) {
                    throw new IllegalStateException("given 준비 실패: " + account + " 계좌를 열지 못했습니다 — " + results.getStatus());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return account;
    }

    private static TransferBatch batchOf(Transfer transfer) {
        TransferBatch batch = new TransferBatch(1);
        batch.add();
        batch.setId(transfer.id());
        batch.setDebitAccountId(transfer.from().id());
        batch.setCreditAccountId(transfer.to().id());
        batch.setAmount(BigInteger.valueOf(transfer.amount()));
        batch.setLedger(transfer.ledger());
        batch.setCode(transfer.code());
        batch.setUserData128(transfer.userData128());
        batch.setUserData64(transfer.userData64());
        batch.setUserData32(transfer.userData32());
        return batch;
    }

    private static long exact(BigInteger amount) {
        return amount.longValueExact();
    }
}
