package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.spi.SubmitOutcome;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/**
 * Turns worker events into Micrometer meters. The two timers publish histogram buckets, so a monitoring system can
 * compute percentiles across app instances (percentiles computed inside one instance cannot be combined).
 *
 * <ul>
 *   <li>{@code ledger.commands.finished} (state, code): commands that reached a final state. {@code code} is TB's
 *       result code for rejections and conflicts, {@code none} otherwise</li>
 *   <li>{@code ledger.command.duration} (state): acceptance to completion</li>
 *   <li>{@code ledger.submit.latency} (outcome), {@code ledger.submit.batch.size}: one per TB request</li>
 *   <li>{@code ledger.stale.writes.rejected}, {@code ledger.handler.failures}</li>
 *   <li>{@code ledger.worker.problems} (kind): halted, malformed_candidate, write_failed, ledger_unavailable,
 *       malformed_reply, no_slots</li>
 * </ul>
 */
public final class MicrometerWorkerEvents implements WorkerEvents {

    private final MeterRegistry registry;
    private final DistributionSummary batchSize;
    private final Counter fenced;
    private final Counter handlerFailures;

    public MicrometerWorkerEvents(MeterRegistry registry) {
        this.registry = registry;
        this.batchSize = DistributionSummary.builder("ledger.submit.batch.size")
                .description("Transfers sent to TB in one request").register(registry);
        this.fenced = Counter.builder("ledger.stale.writes.rejected")
                .description("Writes that hit zero rows: another worker took the command or it had ended (G5)")
                .register(registry);
        this.handlerFailures = Counter.builder("ledger.handler.failures")
                .description("Outcome handler calls that threw; the command runs again after the backoff")
                .register(registry);
    }

    @Override
    public void finished(CommandStatus outcome) {
        String state = lower(outcome.state().name());
        String code = outcome.rejection().map(rejection -> rejection.tbCode().name()).orElse("none");
        Counter.builder("ledger.commands.finished").description("Commands that reached a final state")
                .tag("state", state).tag("code", code).register(registry).increment();
        outcome.completedAt().ifPresent(completed -> Timer.builder("ledger.command.duration")
                .description("Time from acceptance to completion").tag("state", state).publishPercentileHistogram()
                .register(registry).record(Duration.between(outcome.acceptedAt(), completed)));
    }

    @Override
    public void submitCompleted(int batchSize, Duration elapsed, SubmitOutcome outcome) {
        Timer.builder("ledger.submit.latency").description("Time from sending a TB request until it ended")
                .tag("outcome", lower(outcome.name())).publishPercentileHistogram().register(registry).record(elapsed);
        this.batchSize.record(batchSize);
    }

    @Override
    public void fenced(String commandId) {
        fenced.increment();
    }

    @Override
    public void handlerFailed(String commandId, RuntimeException cause) {
        handlerFailures.increment();
    }

    @Override
    public void halted(String commandId) {
        problem("halted");
    }

    @Override
    public void malformedCandidate(String commandId, RuntimeException cause) {
        problem("malformed_candidate");
    }

    @Override
    public void writeFailed(String commandId, RuntimeException cause) {
        problem("write_failed");
    }

    @Override
    public void ledgerUnavailable(Throwable cause) {
        problem("ledger_unavailable");
    }

    @Override
    public void malformedReply(int expected, int actual) {
        problem("malformed_reply");
    }

    @Override
    public void noSlots() {
        problem("no_slots");
    }

    private void problem(String kind) {
        Counter.builder("ledger.worker.problems").description("Worker events a person may need to look at")
                .tag("kind", kind).register(registry).increment();
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
