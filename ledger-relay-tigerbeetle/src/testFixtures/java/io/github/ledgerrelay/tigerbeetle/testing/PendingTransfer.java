package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A transfer sent in the background. It may not have a reply yet. */
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

    /** Waits for the reply and returns the result code. Fails if no reply arrives within the limit. */
    public CreateTransferStatus awaitResult(Duration limit) {
        try {
            return TigerBeetleTestLedger.singleStatus(reply.get(limit.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("No reply to transfer " + transfer + " arrived within " + limit, e);
        } catch (ExecutionException e) {
            throw new AssertionError("Transfer " + transfer + " ended with an error", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
