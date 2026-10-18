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
 * 원장 계좌. 기동할 때 만들고, 이미 있으면 그대로 둔다. 구매자는 처음 한 번 자금 계좌에서 충전받는다. 충전 이체 ID가
 * 고정이라 다시 기동해도 TB가 {@code exists}로 답하고 두 번 충전하지 않는다.
 */
public final class Accounts {

    /** 바트(THB)의 ISO 4217 숫자 코드. TB에서 같은 ledger의 계좌끼리만 돈이 오간다. */
    public static final int LEDGER = 764;
    public static final int SALE_CODE = 1;
    public static final int TOP_UP_CODE = 2;
    public static final long FUNDING = 9001;
    public static final long MERCHANT = 2001;
    public static final List<Long> BUYERS = List.of(1001L, 1002L, 1003L, 1004L, 1005L);
    /** 구매자마다 처음 충전하는 금액. ฿1,000,000.00을 사탕(1/100 바트)으로. */
    public static final long STARTING_BALANCE_SATANG = 100_000_000L;
    static final long TOP_UP_TRANSFER_BASE = 900_000;

    private Accounts() {
    }

    public static boolean isBuyer(long accountId) {
        return BUYERS.contains(accountId);
    }

    /** 계좌를 만들고 구매자를 충전한다. TB가 답할 때까지 기다린다. 이미 했으면 아무것도 바꾸지 않는다. */
    public static void ensure(Client client) {
        AccountBatch accounts = new AccountBatch(BUYERS.size() + 2);
        add(accounts, FUNDING, AccountFlags.NONE);
        add(accounts, MERCHANT, AccountFlags.NONE);
        for (long buyer : BUYERS) {
            // 잔액보다 많이 묶거나 보낼 수 없다. 그래야 잔액이 모자란 결제가 거절된다
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
                    throw new IllegalStateException("원장 계좌를 만들지 못했습니다: " + status);
                }
            }
            CreateTransferResultBatch topUpResults = client.createTransfers(topUps);
            while (topUpResults.next()) {
                CreateTransferStatus status = topUpResults.getStatus();
                if (status != CreateTransferStatus.Created && status != CreateTransferStatus.Exists) {
                    throw new IllegalStateException("구매자를 충전하지 못했습니다: " + status);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("원장 계좌 준비가 중단됐습니다", e);
        }
    }

    /** TB에 있는 계좌의 실제 잔액. */
    public static AccountBalance balanceOf(Client client, long accountId) {
        IdBatch ids = new IdBatch(1);
        ids.add(accountId);
        try {
            AccountBatch found = client.lookupAccounts(ids);
            if (!found.next()) {
                throw new IllegalStateException("원장에 계좌 " + accountId + "가 없습니다");
            }
            return new AccountBalance(found.getDebitsPending().longValueExact(), found.getDebitsPosted().longValueExact(),
                    found.getCreditsPending().longValueExact(), found.getCreditsPosted().longValueExact());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("잔액 조회가 중단됐습니다", e);
        }
    }

    /** 사탕 단위 잔액. pending은 예약으로 묶인 돈, posted는 확정된 돈이다. */
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
