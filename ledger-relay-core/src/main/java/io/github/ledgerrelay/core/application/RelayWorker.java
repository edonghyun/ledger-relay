package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * One worker cycle. The caller owns threads, scheduling and shutdown. Several threads may call {@link #runOnce()} on
 * the same instance at once. The only state cycles share is the in-flight slots.
 *
 * <p>One cycle: take slots → claim → report halted commands → end commands to skip and commands with a known result
 * without TB → record the submission of the rest and send them in one request → write the result for each command
 * separately.
 */
public final class RelayWorker {

    private final CommandLifecycle lifecycle;
    private final LedgerClient ledger;
    private final InFlightLimit slots;
    private final WorkerSettings settings;
    private final WorkerEvents events;

    public RelayWorker(CommandLifecycle lifecycle, LedgerClient ledger, InFlightLimit slots, WorkerSettings settings,
            WorkerEvents events) {
        if (slots.capacity() != settings.maxInFlight()) {
            // The configured maxInFlight must be the real cap. Otherwise changing the setting does not change the
            // concurrency
            throw new IllegalArgumentException("in-flight slots (" + slots.capacity() + ") differ from the configured maxInFlight ("
                    + settings.maxInFlight() + "). Create them with the same value");
        }
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.slots = slots;
        this.settings = settings;
        this.events = events;
    }

    public CycleReport runOnce() {
        // Take slots before the claim. Waiting after the claim blocks while holding the lease, and another worker
        // claims the same command again
        if (!slots.tryTake(settings.batchSize(), settings.pollTimeout())) {
            events.noSlots();
            return CycleReport.IDLE;
        }
        int held = settings.batchSize();
        try {
            ClaimBatch batch = lifecycle.claim(settings.batchSize(), settings.lease());
            batch.halted().forEach(events::halted);
            batch.problems().forEach(problem -> events.malformedCandidate(problem.commandId(), problem.cause()));
            if (batch.claimed().isEmpty()) {
                return CycleReport.IDLE;
            }
            int completed = 0;
            List<ClaimedCommand> sending = new ArrayList<>();
            for (ClaimedCommand claimed : batch.claimed()) {
                if (claimed.skip()) {
                    completed += attempt(claimed, () -> lifecycle.skip(claimed));
                } else if (claimed.knownResult().isPresent()) {
                    completed += attempt(claimed, () -> lifecycle.complete(claimed, claimed.knownResult().get()));
                } else if (attempt(claimed, () -> lifecycle.markSubmitted(claimed)) == 1) {
                    sending.add(claimed);
                }
            }
            if (sending.isEmpty()) {
                return new CycleReport(batch.claimed().size(), 0, completed, 0);
            }
            List<Submission> request = sending.stream().map(ClaimedCommand::submission).toList();
            int sent = request.size();
            CompletableFuture<List<LedgerResultCode>> reply;
            long sentAt = System.nanoTime();
            try {
                reply = ledger.submit(request);
                if (reply == null) {
                    // The client broke its contract. There is no future to attach the slot return to, so treat it as
                    // not sent
                    throw new IllegalStateException("LedgerClient.submit returned null");
                }
            } catch (RuntimeException e) {
                failed(sent, e, sentAt);
                return released(batch, sent, completed, sending);
            }
            // The slots for what was sent come back when the request actually finishes. After a timeout the request
            // still lives in the TB client
            slots.giveBack(held - sent);
            held = 0;
            reply.whenComplete((results, failure) -> slots.giveBack(sent));
            events.submitted(sent);
            List<LedgerResultCode> results;
            try {
                results = reply.get(settings.submitTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // A late response is ignored. Only the slot return is attached to the future
                events.submitTimedOut(sent);
                events.submitCompleted(sent, since(sentAt), SubmitOutcome.TIMED_OUT);
                return released(batch, sent, completed, sending);
            } catch (ExecutionException e) {
                failed(sent, e.getCause(), sentAt);
                return released(batch, sent, completed, sending);
            } catch (InterruptedException e) {
                events.submitCompleted(sent, since(sentAt), SubmitOutcome.FAILED);
                CycleReport report = released(batch, sent, completed, sending);
                Thread.currentThread().interrupt();
                return report;
            }
            if (results.size() != sent) {
                // Results are paired by position, so a single shift ends commands with another command's result. Write
                // nothing
                events.malformedReply(sent, results.size());
                events.submitCompleted(sent, since(sentAt), SubmitOutcome.MALFORMED_REPLY);
                return released(batch, sent, completed, sending);
            }
            events.submitCompleted(sent, since(sentAt), SubmitOutcome.REPLIED);
            for (int i = 0; i < sending.size(); i++) {
                ClaimedCommand claimed = sending.get(i);
                LedgerResultCode code = results.get(i);
                // Write each command separately, so one failure does not lose the results the others received
                completed += attempt(claimed, () -> lifecycle.complete(claimed, code));
            }
            return new CycleReport(batch.claimed().size(), sent, completed, 0);
        } finally {
            slots.giveBack(held);
        }
    }

    /** Reports an unrecoverable client failure separately from a transient failure. */
    private void failed(int sent, Throwable cause, long sentAt) {
        if (cause instanceof LedgerUnavailableException) {
            events.ledgerUnavailable(cause);
            events.submitCompleted(sent, since(sentAt), SubmitOutcome.LEDGER_UNAVAILABLE);
        } else {
            events.submitFailed(sent, cause);
            events.submitCompleted(sent, since(sentAt), SubmitOutcome.FAILED);
        }
    }

    private static Duration since(long nanoTime) {
        return Duration.ofNanos(System.nanoTime() - nanoTime);
    }

    /** Puts back every sent command. After the backoff they are claimed again with the same transfer id. */
    private CycleReport released(ClaimBatch batch, int sent, int completed, List<ClaimedCommand> sending) {
        int released = 0;
        for (ClaimedCommand claimed : sending) {
            released += attempt(claimed, () -> lifecycle.release(claimed));
        }
        return new CycleReport(batch.claimed().size(), sent, completed, released);
    }

    /**
     * Performs one write. If it ends with an exception, it reports only that command and returns 0. That command stays
     * in flight until its lease expires and is claimed again with the same transfer id. The other commands in the same
     * cycle keep being written. It does not swallow an Error.
     *
     * @return 1 if the write took effect. Zero rows, a handler failure and a write failure are reported and return 0
     */
    private int attempt(ClaimedCommand claimed, Supplier<WriteResult> write) {
        WriteResult result;
        try {
            result = write.get();
        } catch (RuntimeException e) {
            events.writeFailed(claimed.commandId(), e);
            return 0;
        }
        return switch (result) {
            case WriteResult.Written written -> {
                written.finished().ifPresent(events::finished);
                yield 1;
            }
            case WriteResult.Fenced fenced -> {
                events.fenced(claimed.commandId());
                yield 0;
            }
            case WriteResult.HandlerFailed failed -> {
                events.handlerFailed(claimed.commandId(), failed.cause());
                yield 0;
            }
        };
    }
}
