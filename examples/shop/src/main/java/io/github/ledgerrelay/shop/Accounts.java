package io.github.ledgerrelay.shop;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import java.util.List;

/**
 * Ledger accounts. Created at startup and left as they are if they already exist. Each buyer is topped up once from
 * the funding account. The top-up transfer ids are fixed, so after a restart TB answers {@code exists} and no buyer is
 * topped up twice.
 */
public final class Accounts {

    /** ISO 4217 numeric code of the baht (THB). In TB, money moves only between accounts of the same ledger. */
    public static final int LEDGER = 764;
    public static final int SALE_CODE = 1;
    public static final int TOP_UP_CODE = 2;
    public static final long FUNDING = 9001;
    public static final long MERCHANT = 2001;
    public static final List<Long> BUYERS = List.of(1001L, 1002L, 1003L, 1004L, 1005L);
    /** Initial top-up per buyer: ฿1,000,000.00 in satang (1/100 baht). */
    public static final long STARTING_BALANCE_SATANG = 100_000_000L;
    static final long TOP_UP_TRANSFER_BASE = 900_000;

    private Accounts() {
    }

    public static boolean isBuyer(long accountId) {
        return BUYERS.contains(accountId);
    }

    /** Creates the accounts and tops up the buyers. Waits until TB answers. Changes nothing if already done. */
    public static void ensure(Client client) {
        AccountBatch accounts = new AccountBatch(BUYERS.size() + 2);
        add(accounts, FUNDING, AccountFlags.NONE);
        add(accounts, MERCHANT, AccountFlags.NONE);
        for (long buyer : BUYERS) {
            // A buyer cannot hold or send more than the balance, so a payment with insufficient funds is rejected
            add(accounts, buyer, AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
        }
        TransferBatch topUps = new TransferBatch(BUYERS.size());
        for (long buyer : BUYERS) {
            topUps.add();
            topUps.setId(TOP_UP_TRANSFER_BASE + buyer);
            topUps.setDebitAccountId(FUNDING);
            topUps.setCreditAccountId(buyer);
            topUps.setAmount(STARTING_BALANCE_SATANG);
            topUps.setLedger(LEDGER);
            topUps.setCode(TOP_UP_CODE);
        }
        try {
            CreateAccountResultBatch accountResults = client.createAccounts(accounts);
            while (accountResults.next()) {
                CreateAccountStatus status = accountResults.getStatus();
                if (status != CreateAccountStatus.Created && status != CreateAccountStatus.Exists) {
                    throw new IllegalStateException("Could not create the ledger accounts: " + status);
                }
            }
            CreateTransferResultBatch topUpResults = client.createTransfers(topUps);
            while (topUpResults.next()) {
                CreateTransferStatus status = topUpResults.getStatus();
                if (status != CreateTransferStatus.Created && status != CreateTransferStatus.Exists) {
                    throw new IllegalStateException("Could not top up the buyers: " + status);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preparing the ledger accounts", e);
        }
    }

    /** Actual balance of an account in TB. */
    public static AccountBalance balanceOf(Client client, long accountId) {
        IdBatch ids = new IdBatch(1);
        ids.add(accountId);
        try {
            AccountBatch found = client.lookupAccounts(ids);
            if (!found.next()) {
                throw new IllegalStateException("Account " + accountId + " does not exist in the ledger");
            }
            return new AccountBalance(found.getDebitsPending().longValueExact(), found.getDebitsPosted().longValueExact(),
                    found.getCreditsPending().longValueExact(), found.getCreditsPosted().longValueExact());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while looking up the balance", e);
        }
    }

    /** Balances in satang. pending is money held by reservations, posted is money that has been posted. */
    public record AccountBalance(long debitsPending, long debitsPosted, long creditsPending, long creditsPosted) {
    }

    private static void add(AccountBatch batch, long id, int flags) {
        batch.add();
        batch.setId(id);
        batch.setLedger(LEDGER);
        batch.setCode(SALE_CODE);
        batch.setFlags(flags);
    }
}
