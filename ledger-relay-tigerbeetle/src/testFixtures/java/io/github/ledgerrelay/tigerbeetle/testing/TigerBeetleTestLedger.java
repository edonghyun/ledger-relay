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
import com.tigerbeetle.UInt128;
import java.math.BigInteger;
import java.time.Duration;

/**
 * Vocabulary for tests that work with a real TigerBeetle. Opens accounts, sends transfers, reads balances.
 *
 * <p>The given-only methods ({@link #recorded}, {@link #rejected}) stop on the spot with "given setup failed" when the
 * precondition does not hold. This keeps a test from reaching the then step and failing for an unrelated reason.
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

    /** Opens an account on {@code ledger}. Accounts on different ledgers cannot transfer to each other. */
    public Account openAccount(String name, int ledger) {
        return open(Account.notOpened(name), AccountFlags.NONE, ledger);
    }

    /** An account that cannot be debited beyond its balance (a wallet). A new account has a balance of 0. */
    public Account openAccountThatCannotOverdraw(String name) {
        return open(Account.notOpened(name), AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
    }

    /** given: opens a not-yet-opened account ({@link Account#notOpened}) with its existing id. */
    public Account open(Account account) {
        return open(account, AccountFlags.NONE);
    }

    /** Deposits {@code amount} from a deposit account outside the test. */
    public void deposit(Account to, long amount) {
        if (depositSource == null) {
            depositSource = openAccount("deposit source");
        }
        recorded(Transfer.transfer(amount).from(depositSource).to(to));
    }

    /** when: sends the transfer and returns the result code. */
    public CreateTransferStatus submit(Transfer transfer) {
        try {
            return singleStatus(client.createTransfers(batchOf(transfer)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** when: sends the transfer without waiting for the reply. */
    public PendingTransfer submitInBackground(Transfer transfer) {
        return new PendingTransfer(transfer, client.createTransfersAsync(batchOf(transfer)));
    }

    /** given: this transfer is already recorded. */
    public Transfer recorded(Transfer transfer) {
        CreateTransferStatus result = submit(transfer);
        if (result != CreateTransferStatus.Created) {
            throw new IllegalStateException(
                    "given setup failed: transfer " + transfer + " should have been recorded, but the result is "
                            + result);
        }
        return transfer;
    }

    /** given: this transfer has been rejected with {@code reason}. */
    public Transfer rejected(Transfer transfer, CreateTransferStatus reason) {
        CreateTransferStatus result = submit(transfer);
        if (result != reason) {
            throw new IllegalStateException(
                    "given setup failed: transfer " + transfer + " should have been rejected for " + reason
                            + ", but the result is " + result);
        }
        return transfer;
    }

    /**
     * Reads a transfer as actually stored in the ledger. Used to check the values a post or void inherits from the
     * reservation.
     */
    public StoredTransfer stored(Transfer transfer) {
        return stored(transfer.id(), transfer.toString());
    }

    /** Reads the transfer stored in the ledger by transfer id. Used to check transfers sent by the module's adapter. */
    public StoredTransfer stored(BigInteger transferId) {
        return stored(UInt128.asBytes(transferId), "ID " + transferId);
    }

    private StoredTransfer stored(byte[] id, String shown) {
        IdBatch ids = new IdBatch(1);
        ids.add(id);
        try {
            TransferBatch found = client.lookupTransfers(ids);
            if (!found.next()) {
                throw new IllegalStateException("Transfer " + shown + " is not in the ledger");
            }
            return new StoredTransfer(found.getAmount(), found.getDebitAccountId(), found.getCreditAccountId(),
                    found.getLedger(), found.getCode(), UInt128.asBigInteger(found.getUserData128()), found.getUserData64(),
                    found.getUserData32(),
                    Integer.toUnsignedLong(found.getTimeout()), found.getTimestamp());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * given: closes this account. Records a zero-amount closing reservation ({@code PENDING | CLOSING_DEBIT}) out of
     * this account. A closed account allows only voiding reservations.
     */
    public Transfer closeAccount(Account account) {
        if (depositSource == null) {
            depositSource = openAccount("deposit source");
        }
        Transfer closing = Transfer.reservation(0).from(account).to(depositSource);
        return recorded(new Transfer(closing.id(), account, depositSource, BigInteger.ZERO, closing.ledger(),
                closing.code(), 0, 0, 0, TransferFlags.PENDING | TransferFlags.CLOSING_DEBIT, null, 0));
    }

    /**
     * given: waits until the reservation expires. TB releases the held amount of an expired reservation by itself, so
     * this watches the sending account's pending debits drop to 0. The sending account must have no other reservation.
     */
    public void waitUntilExpired(Transfer hold) {
        long deadline = System.nanoTime() + EXPIRY_LIMIT.toNanos();
        while (balanceOf(hold.from()).debitsPending() != 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("given setup failed: reservation " + hold + " did not expire within "
                        + EXPIRY_LIMIT.toSeconds() + " seconds");
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
                throw new IllegalStateException("Account " + account + " is not in the ledger");
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
            throw new IllegalStateException("The result is empty");
        }
        CreateTransferStatus status = results.getStatus();
        if (results.next()) {
            throw new IllegalStateException("The result has more than one entry");
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
                    throw new IllegalStateException("given setup failed: could not open account " + account + " — "
                            + results.getStatus());
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

    /** A post or void sends the accounts empty (0) so it inherits the reservation's accounts. */
    private static byte[] idOrZero(Account account) {
        return account == null ? new byte[16] : account.id();
    }

    private static long exact(BigInteger amount) {
        return amount.longValueExact();
    }
}
