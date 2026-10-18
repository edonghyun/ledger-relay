package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.CommandBacklog;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Gauges read from the backlog sample and the in-flight slots. Before the first sample the backlog gauges read NaN
 * (unknown), not 0. The auto-configuration binds them to the app's registry once at startup.
 *
 * <ul>
 *   <li>{@code ledger.commands.waiting} (state = pending, claimed, halted)</li>
 *   <li>{@code ledger.commands.oldest.age}: seconds the oldest pending or claimed command has waited. 0 when none</li>
 *   <li>{@code ledger.inflight}: in-flight slots taken, when the worker runs</li>
 *   <li>{@code ledger.backlog.sample.failures}</li>
 * </ul>
 */
final class LedgerRelayMeters {

    private final BacklogSampler sampler;
    private final Optional<InFlightLimit> slots;

    LedgerRelayMeters(BacklogSampler sampler, Optional<InFlightLimit> slots) {
        this.sampler = sampler;
        this.slots = slots;
    }

    void bindTo(MeterRegistry registry) {
        waiting(registry, "pending", CommandBacklog::pending);
        waiting(registry, "claimed", CommandBacklog::claimed);
        waiting(registry, "halted", CommandBacklog::halted);
        Gauge.builder("ledger.commands.oldest.age", sampler, s -> s.latest()
                        .map(backlog -> backlog.oldestWaitingAge().orElse(Duration.ZERO).toMillis() / 1000.0)
                        .orElse(Double.NaN))
                .description("Seconds the oldest pending or claimed command has waited").baseUnit("seconds")
                .register(registry);
        FunctionCounter.builder("ledger.backlog.sample.failures", sampler, BacklogSampler::failures)
                .description("Backlog reads that failed; the gauges keep the last sample").register(registry);
        slots.ifPresent(limit -> Gauge.builder("ledger.inflight", limit, InFlightLimit::inUse)
                .description("Transfers in flight to TB right now").register(registry));
    }

    private void waiting(MeterRegistry registry, String state, ToDoubleFunction<CommandBacklog> count) {
        Gauge.builder("ledger.commands.waiting", sampler,
                        s -> s.latest().map(count::applyAsDouble).orElse(Double.NaN))
                .description("Commands waiting right now, by stored state").tag("state", state).register(registry);
    }
}
