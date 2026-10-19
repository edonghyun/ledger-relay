package io.github.ledgerrelay.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes {@code results.csv} (one line per run, appended as runs finish) and {@code summary.md} (per condition). */
final class ResultWriter {

    private static final String HEADER = "label,measure,workers,keys,batch,commands,rate,repetition,completed_per_s,"
            + "accept_p99_us,finish_p50_ms,finish_p99_ms,passed,accepted,unfinished,mismatches,applied,"
            + "transfers_in_ledger,submit_p50_us,submit_p99_us,avg_batch,requests,failed_requests,idle_ratio,no_slots,"
            + "fenced,write_failures,pg_waits,note";

    private final Path directory;
    private final List<RunResult> results = new ArrayList<>();

    ResultWriter(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("results.csv"), HEADER + "\n");
    }

    void add(RunResult result) throws IOException {
        results.add(result);
        Files.writeString(directory.resolve("results.csv"), csv(result) + "\n", java.nio.file.StandardOpenOption.APPEND);
    }

    List<RunResult> results() {
        return List.copyOf(results);
    }

    void summary(String environment) throws IOException {
        StringBuilder out = new StringBuilder("# Benchmark summary\n\n").append(environment).append("\n\n");
        Map<String, List<RunResult>> byLabel = results.stream()
                .collect(Collectors.groupingBy(r -> r.condition().label(), LinkedHashMap::new, Collectors.toList()));
        out.append("| condition | runs passed | completed/s median [min–max] | accept p99 µs | finish p50 ms | "
                + "finish p99 ms | TB request p50/p99 µs | avg batch | idle cycles | top PG waits | notes |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var entry : byLabel.entrySet()) {
            List<RunResult> runs = entry.getValue();
            List<RunResult> passed = runs.stream().filter(r -> r.verdict().passed()).toList();
            List<Double> rates = passed.stream().map(RunResult::completedPerSecond).toList();
            RunResult typical = passed.stream().sorted(Comparator.comparing(RunResult::completedPerSecond))
                    .skip(passed.size() / 2).findFirst().orElse(runs.get(0));
            Observations o = typical.observations();
            out.append("| ").append(entry.getKey())
                    .append(" | ").append(passed.size()).append("/").append(runs.size())
                    .append(" | ").append(rates.isEmpty() ? "–" : String.format(Locale.ROOT, "%.0f [%.0f–%.0f]",
                            Percentiles.median(rates), rates.stream().mapToDouble(d -> d).min().orElse(0),
                            rates.stream().mapToDouble(d -> d).max().orElse(0)))
                    .append(" | ").append(medianOf(passed, RunResult::acceptP99Micros))
                    .append(" | ").append(medianOf(passed, RunResult::finishP50Millis))
                    .append(" | ").append(medianOf(passed, RunResult::finishP99Millis))
                    .append(" | ").append(o.submitP50Micros()).append("/").append(o.submitP99Micros())
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", o.averageBatch()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f%%", 100 * o.idleRatio()))
                    .append(" | ").append(waits(o))
                    .append(" | ").append(runs.stream().map(RunResult::note).filter(n -> !n.isEmpty()).distinct()
                            .collect(Collectors.joining("; ")))
                    .append(" |\n");
        }
        Files.writeString(directory.resolve("summary.md"), out.toString());
    }

    private static String medianOf(List<RunResult> runs, java.util.function.Function<RunResult, Long> value) {
        List<Double> values = runs.stream().map(value).filter(v -> v != null).map(Long::doubleValue).toList();
        return values.isEmpty() ? "–" : String.format(Locale.ROOT, "%.0f", Percentiles.median(values));
    }

    private static String waits(Observations o) {
        return o.pgWaits().entrySet().stream().limit(3)
                .map(e -> e.getKey() + " " + Math.round(100 * e.getValue()) + "%").collect(Collectors.joining(", "));
    }

    private static String csv(RunResult r) {
        Condition c = r.condition();
        Observations o = r.observations();
        Correctness.Verdict v = r.verdict();
        return String.join(",", quote(c.label()), c.measure(), str(c.workers()), c.keys().name(), str(c.batchSize()),
                str(c.commands()), String.format(Locale.ROOT, "%.0f", c.rate()), str(r.repetition()),
                r.completedPerSecond() == null ? "" : String.format(Locale.ROOT, "%.1f", r.completedPerSecond()),
                str(r.acceptP99Micros()), str(r.finishP50Millis()), str(r.finishP99Millis()), str(v.passed()),
                str(v.accepted()), str(v.unfinished()), str(v.mismatches()), str(v.applied()), str(v.transfersInLedger()),
                str(o.submitP50Micros()), str(o.submitP99Micros()), String.format(Locale.ROOT, "%.2f", o.averageBatch()),
                str(o.requests()), str(o.failedRequests()), String.format(Locale.ROOT, "%.3f", o.idleRatio()),
                str(o.noSlots()), str(o.fenced()), str(o.writeFailures()), quote(waits(o)), quote(r.note()));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "'") + "\"";
    }
}
