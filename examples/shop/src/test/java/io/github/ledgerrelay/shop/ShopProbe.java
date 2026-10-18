package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.shop.order.OrderState;
import io.github.ledgerrelay.shop.order.Orders;
import io.github.ledgerrelay.shop.order.ShopOrder;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 테스트에서 주문이 어디까지 갔는지와 원장의 실제 잔액을 본다. */
public final class ShopProbe {

    /** TB를 끊었다 이은 뒤 재시도 backoff까지 넉넉히 기다린다. */
    public static final Duration SETTLE = Duration.ofSeconds(20);

    private final Orders orders;
    private final Client client;
    private final JdbcClient sql;

    public ShopProbe(Orders orders, Client client, DataSource dataSource) {
        this.orders = orders;
        this.client = client;
        this.sql = JdbcClient.create(dataSource);
    }

    /**
     * 대기·처리 중인 명령이 없을 때까지 기다린다. 앞 테스트가 남긴 명령이 잔액을 바꾸는 중에 "전" 잔액을 재지 않으려고, 잔액의
     * 차이를 보는 테스트가 given 맨 앞에서 부른다. 멈춘(HALTED) 명령은 운영자가 풀기 전에는 끝나지 않으므로 세지 않는다.
     */
    public void awaitQuiet() throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            long busy = sql.sql("SELECT count(*) FROM ledger_command WHERE state IN ('PENDING','CLAIMED')")
                    .query(Long.class).single();
            if (busy == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("대기·처리 중인 명령이 " + SETTLE + " 안에 비지 않았다");
    }

    public ShopOrder awaitState(long orderId, OrderState expected) throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        ShopOrder last = null;
        while (System.nanoTime() < deadline) {
            last = orders.find(orderId).orElseThrow();
            if (last.state() == expected) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("주문 " + orderId + "이 " + SETTLE + " 안에 " + expected + "가 되지 않았다. 마지막 상태: " + last);
    }

    /** 구매자 돈 중 예약으로 묶인 금액(사탕). */
    public long heldBy(long buyer) {
        return Accounts.balanceOf(client, buyer).debitsPending();
    }

    /** 가맹점이 확정으로 받은 금액(사탕). */
    public long merchantReceived() {
        return Accounts.balanceOf(client, Accounts.MERCHANT).creditsPosted();
    }

    /** given 준비: 구매자가 쓸 수 있는 돈을 자금 계좌로 모두 돌려보낸다. 그 뒤 결제는 잔액 부족으로 거절된다. */
    public void drain(long buyer) throws InterruptedException {
        Accounts.AccountBalance balance = Accounts.balanceOf(client, buyer);
        long available = balance.creditsPosted() - balance.debitsPosted() - balance.debitsPending();
        if (available == 0) {
            return;
        }
        TransferBatch drain = new TransferBatch(1);
        drain.add();
        drain.setId(UInt128.id());
        drain.setDebitAccountId(buyer);
        drain.setCreditAccountId(Accounts.FUNDING);
        drain.setAmount(available);
        drain.setLedger(Accounts.LEDGER);
        drain.setCode(Accounts.TOP_UP_CODE);
        CreateTransferResultBatch result = client.createTransfers(drain);
        if (result.next() && result.getStatus() != CreateTransferStatus.Created) {
            throw new IllegalStateException("given 준비 실패: 구매자 " + buyer + "를 비우지 못했다 — " + result.getStatus());
        }
    }
}
