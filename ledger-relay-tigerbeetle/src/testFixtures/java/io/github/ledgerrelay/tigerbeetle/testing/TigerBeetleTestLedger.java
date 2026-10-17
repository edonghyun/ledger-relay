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
import com.tigerbeetle.TransferFlags;
import java.math.BigInteger;
import java.time.Duration;

/**
 * 테스트가 실제 TigerBeetle을 다루는 말. 계좌를 열고, 이체를 보내고, 잔액을 본다.
 *
 * <p>given 전용 메서드({@link #recorded}, {@link #rejected})는 전제가 성립하지 않으면 그 자리에서
 * "given 준비 실패"로 멈춘다. then 단계까지 가서 엉뚱한 이유로 실패하지 않게 하려는 것이다.
 */
public final class TigerBeetleTestLedger implements AutoCloseable {

    public static final int LEDGER = 1;
    public static final int CODE = 1;

    private static final Duration EXPIRY_LIMIT = Duration.ofSeconds(2);

    private final Client client;
    private Account depositSource;

    public TigerBeetleTestLedger(Client client) {
        this.client = client;
    }

    public Account openAccount(String name) {
        return open(Account.notOpened(name), AccountFlags.NONE);
    }

    /** {@code ledger}에 계좌를 연다. 다른 ledger의 계좌끼리는 이체할 수 없다. */
    public Account openAccount(String name, int ledger) {
        return open(Account.notOpened(name), AccountFlags.NONE, ledger);
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

    /** 원장에 실제로 저장된 이체를 읽는다. 확정·해제가 예약에서 물려받은 값을 확인할 때 쓴다. */
    public StoredTransfer stored(Transfer transfer) {
        IdBatch ids = new IdBatch(1);
        ids.add(transfer.id());
        try {
            TransferBatch found = client.lookupTransfers(ids);
            if (!found.next()) {
                throw new IllegalStateException(transfer + " 이체가 원장에 없습니다");
            }
            return new StoredTransfer(found.getAmount(), found.getDebitAccountId(), found.getCreditAccountId(),
                    found.getLedger(), found.getCode(), found.getUserData64(), found.getUserData32(),
                    Integer.toUnsignedLong(found.getTimeout()), found.getTimestamp());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * given: 이 계좌를 닫는다. 이 계좌에서 나가는 0원 닫기 예약({@code PENDING | CLOSING_DEBIT})을 기록한다.
     * 닫힌 계좌에서는 예약 해제만 허용된다.
     */
    public Transfer closeAccount(Account account) {
        if (depositSource == null) {
            depositSource = openAccount("입금 계좌");
        }
        Transfer closing = Transfer.reservation(0).from(account).to(depositSource);
        return recorded(new Transfer(closing.id(), account, depositSource, BigInteger.ZERO, closing.ledger(),
                closing.code(), 0, 0, 0, TransferFlags.PENDING | TransferFlags.CLOSING_DEBIT, null, 0));
    }

    /**
     * given: 예약이 만료될 때까지 기다린다. TB는 만료된 예약의 보류 금액을 스스로 풀므로, 보내는 계좌의 보류 출금이 0이
     * 되는 것을 본다. 보내는 계좌에 다른 예약이 없어야 한다.
     */
    public void waitUntilExpired(Transfer hold) {
        long deadline = System.nanoTime() + EXPIRY_LIMIT.toNanos();
        while (balanceOf(hold.from()).debitsPending() != 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("given 준비 실패: " + hold + " 예약이 "
                        + EXPIRY_LIMIT.toSeconds() + "초 안에 만료되지 않았습니다");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
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
        return open(account, flags, LEDGER);
    }

    private Account open(Account account, int flags, int ledger) {
        AccountBatch batch = new AccountBatch(1);
        batch.add();
        batch.setId(account.id());
        batch.setLedger(ledger);
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
        batch.setDebitAccountId(idOrZero(transfer.from()));
        batch.setCreditAccountId(idOrZero(transfer.to()));
        batch.setAmount(transfer.amount());
        batch.setLedger(transfer.ledger());
        batch.setCode(transfer.code());
        batch.setUserData128(transfer.userData128());
        batch.setUserData64(transfer.userData64());
        batch.setUserData32(transfer.userData32());
        batch.setFlags(transfer.flags());
        if (transfer.pendingId() != null) {
            batch.setPendingId(transfer.pendingId());
        }
        batch.setTimeout((int) transfer.timeoutSeconds());
        return batch;
    }

    /** 확정·해제는 계좌를 비워(0) 보내 예약의 계좌를 물려받는다. */
    private static byte[] idOrZero(Account account) {
        return account == null ? new byte[16] : account.id();
    }

    private static long exact(BigInteger amount) {
        return amount.longValueExact();
    }
}
