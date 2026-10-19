package io.github.ledgerrelay.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the run looked like from inside, for the bottleneck notes: TB request times, how full the batches were, how
 * often a worker found nothing to do, and what PostgreSQL sessions were waiting on.
 *
 * @param idleRatio      cycles that claimed nothing, out of all cycles
 * @param pgWaits        share of sampled active sessions per wait event ({@code CPU} when not waiting), largest first
 */
public record Observations(Long submitP50Micros, Long submitP99Micros, double averageBatch, long requests,
        long failedRequests, double idleRatio, long noSlots, long fenced, long writeFailures,
        Map<String, Double> pgWaits) {

    static Observations of(Recorder recorder, Map<String, Long> waitSamples) {
        long cycles = recorder.cycles.sum();
        long requests = recorder.requests.sum();
        long total = waitSamples.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Double> waits = new LinkedHashMap<>();
        waitSamples.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .forEach(e -> waits.put(e.getKey(), total == 0 ? 0 : (double) e.getValue() / total));
        var nanos = recorder.submitNanos();
        Long p50 = Percentiles.of(nanos, 50);
        Long p99 = Percentiles.of(nanos, 99);
        return new Observations(p50 == null ? null : p50 / 1000, p99 == null ? null : p99 / 1000,
                requests == 0 ? 0 : (double) recorder.transfersSent.sum() / requests, requests,
                recorder.failedRequests.sum(), cycles == 0 ? 0 : (double) recorder.idleCycles.sum() / cycles,
                recorder.noSlots.sum(), recorder.fenced.sum(), recorder.writeFailures.sum(), waits);
    }
}
