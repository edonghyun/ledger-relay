package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 백그라운드로 보낸 이체. 응답이 아직 없을 수 있다. */
public final class PendingTransfer {

    private final Transfer transfer;
    private final CompletableFuture<CreateTransferResultBatch> reply;

    PendingTransfer(Transfer transfer, CompletableFuture<CreateTransferResultBatch> reply) {
        this.transfer = transfer;
        this.reply = reply;
    }

    public Transfer transfer() {
        return transfer;
    }

    public boolean hasReply() {
        return reply.isDone();
    }

    /** 응답을 기다려 결과 코드를 돌려준다. 시간 안에 응답이 없으면 실패한다. */
    public CreateTransferStatus awaitResult(Duration limit) {
        try {
            return TigerBeetleTestLedger.singleStatus(reply.get(limit.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError(limit + " 안에 " + transfer + " 이체의 응답이 오지 않았습니다", e);
        } catch (ExecutionException e) {
            throw new AssertionError(transfer + " 이체가 오류로 끝났습니다", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
