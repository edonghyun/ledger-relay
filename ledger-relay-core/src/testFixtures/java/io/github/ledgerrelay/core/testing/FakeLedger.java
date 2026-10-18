package io.github.ledgerrelay.core.testing;

import java.util.Objects;
import io.github.ledgerrelay.core.spi.TransferBody;
import io.github.ledgerrelay.core.spi.LedgerTransfer;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.Submission;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A fake TB for worker tests. A transfer with no set answer ends as {@code Created}. You can set a result per transfer
 * ({@link #answer}), hold the replies ({@link #holdReplies}), fail the next request ({@link #failNext}), or return the
 * wrong number of results ({@link #replyWithCount}). {@link #requests()} shows the requests received. The worker
 * returns
 * slots from a future callback, so this class is thread-safe.
 */
public final class FakeLedger implements LedgerClient {

    private final Map<BigInteger, LedgerResultCode> answers = new HashMap<>();
    private final List<List<Submission>> requests = new ArrayList<>();
    private final List<Held> held = new ArrayList<>();
    private boolean holding;
    private RuntimeException nextFailure;
    private Integer nextCount;
    private RuntimeException nextThrow;
    private boolean nextNull;
    private boolean holdingLookups;
    private final List<Runnable> heldLookups = new java.util.ArrayList<>();
    private int lookups;
    /** Transfers TB recorded (those answered with Created or Exists). Reconciliation looks them up. */
    private final Map<BigInteger, LedgerTransfer> stored = new HashMap<>();

    /** Answer this result for this transfer id. */
    public synchronized void answer(BigInteger transferId, LedgerResultCode code) {
        answers.put(transferId, code);
    }

    /** Holds the replies of later requests. Models the TB client having no timeout, so a reply never arrives. */
    public synchronized void holdReplies() {
        holding = true;
    }

    /** Completes every held reply, and answers later requests right away. */
    public void releaseHeldReplies() {
        List<Held> releasing;
        synchronized (this) {
            holding = false;
            releasing = List.copyOf(held);
            held.clear();
        }
        releasing.forEach(h -> h.reply().complete(h.results()));
    }

    /** Ends the next request with this exception. */
    public synchronized void failNext(RuntimeException cause) {
        nextFailure = cause;
    }

    /**
     * The next request returns no future and throws this exception at the send call (closed client, failure to fill the
     * batch).
     */
    public synchronized void throwOnNextSubmit(RuntimeException failure) {
        nextThrow = failure;
    }

    /** The next request returns null instead of a future (a client that breaks the contract). */
    public synchronized void replyWithNull() {
        nextNull = true;
    }

    /** Deletes this transfer as if TB lost it (to see whether reconciliation finds the mismatch). */
    public synchronized void forget(BigInteger transferId) {
        stored.remove(transferId);
    }

    /** Plants this transfer as if it existed in TB. */
    public synchronized void plant(LedgerTransfer transfer) {
        stored.put(transfer.transferId(), transfer);
    }

    /** Changes the amount of a recorded transfer. */
    public synchronized void alterAmount(BigInteger transferId, BigInteger amount) {
        LedgerTransfer t = stored.get(transferId);
        stored.put(transferId, new LedgerTransfer(t.transferId(), t.debitAccountId(), t.creditAccountId(), amount,
                t.ledger(), t.code(), t.kind()));
    }

    /** The number of lookup requests received. */
    public synchronized int lookups() {
        return lookups;
    }

    /** Stops answering later lookups. */
    public synchronized void holdLookups() {
        holdingLookups = true;
    }

    /** Answers the held lookups now. Later lookups stay held. Models a TB that answers late. */
    public void answerHeldLookups() {
        List<Runnable> answers;
        synchronized (this) {
            answers = List.copyOf(heldLookups);
            heldLookups.clear();
        }
        answers.forEach(Runnable::run);
    }

    @Override
    public synchronized CompletableFuture<List<LedgerTransfer>> lookup(List<BigInteger> transferIds) {
        lookups++;
        if (holdingLookups) {
            CompletableFuture<List<LedgerTransfer>> held = new CompletableFuture<>();
            List<LedgerTransfer> found = transferIds.stream().map(stored::get).filter(Objects::nonNull).toList();
            heldLookups.add(() -> held.complete(found));
            return held;
        }
        return CompletableFuture.completedFuture(transferIds.stream().map(stored::get).filter(Objects::nonNull).toList());
    }

    /** Returns only n results for the next request. If n exceeds the request, the extra ones are Created. */
    public synchronized void replyWithCount(int n) {
        nextCount = n;
    }

    public synchronized List<List<Submission>> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized CompletableFuture<List<LedgerResultCode>> submit(List<Submission> batch) {
        requests.add(List.copyOf(batch));
        if (nextThrow != null) {
            RuntimeException failure = nextThrow;
            nextThrow = null;
            throw failure;
        }
        if (nextNull) {
            nextNull = false;
            return null;
        }
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            return CompletableFuture.failedFuture(failure);
        }
        List<LedgerResultCode> results = new ArrayList<>();
        for (Submission submission : batch) {
            LedgerResultCode code = answers.getOrDefault(submission.transferId(), LedgerResultCode.Created);
            results.add(code);
            if (code == LedgerResultCode.Created || code == LedgerResultCode.Exists) {
                TransferBody body = submission.body();
                stored.putIfAbsent(submission.transferId(), new LedgerTransfer(submission.transferId(),
                        body.debitAccountId(), body.creditAccountId(), body.amount(), body.ledger(), body.code(),
                        body.kind()));
            }
        }
        if (nextCount != null) {
            results = new ArrayList<>(results.subList(0, Math.min(nextCount, results.size())));
            while (results.size() < nextCount) {
                results.add(LedgerResultCode.Created);
            }
            nextCount = null;
        }
        CompletableFuture<List<LedgerResultCode>> reply = new CompletableFuture<>();
        if (holding) {
            held.add(new Held(reply, List.copyOf(results)));
        } else {
            reply.complete(List.copyOf(results));
        }
        return reply;
    }

    private record Held(CompletableFuture<List<LedgerResultCode>> reply, List<LedgerResultCode> results) {
    }
}
