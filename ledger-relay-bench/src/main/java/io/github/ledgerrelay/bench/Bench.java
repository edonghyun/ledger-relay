package io.github.ledgerrelay.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line of the load measurement.
 *
 * <pre>
 * matrix --out DIR [--repeat 3]                      the whole condition table of docs/benchmarks.md
 * single --measure drain|steady --workers N --keys spread|hundred|one --batch N
 *        [--commands N] [--rate R] [--seconds S] [--warmup S] [--repeat N] --out DIR
 * </pre>
 *
 * Exits with 1 when any run failed its correctness check.
 */
public final class Bench {

    static final int[] WORKERS = {1, 2, 4, 8};
    static final int BATCH = 128;
    /** One key is processed one command at a time, so it gets fewer commands to keep the run short. */
    static final Map<KeySpread, Integer> DRAIN_COMMANDS = Map.of(KeySpread.SPREAD, 20_000, KeySpread.HUNDRED, 20_000,
            KeySpread.ONE, 2_000);
    static final Duration STEADY = Duration.ofSeconds(30);
    static final Duration WARMUP = Duration.ofSeconds(5);
    static final double[] LOADS = {0.5, 0.8};

    private Bench() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path out = Path.of(options.getOrDefault("out", "build/bench/" + Instant.now().getEpochSecond()));
        int repeat = Integer.parseInt(options.getOrDefault("repeat", "3"));
        ResultWriter writer = new ResultWriter(out);
        try (BenchEnvironment environment = BenchEnvironment.start()) {
            String about = Machine.describe(environment);
            if (args[0].equals("matrix")) {
                matrix(environment, writer, repeat, about);
            } else {
                for (int r = 1; r <= repeat; r++) {
                    record(writer, measure(environment, single(options), r), about);
                }
            }
            writer.summary(about);
        }
        boolean allPassed = writer.results().stream().allMatch(r -> r.verdict().passed());
        System.out.println("Results in " + out.toAbsolutePath() + (allPassed ? "" : " — SOME RUNS FAILED THE CHECK"));
        System.exit(allPassed ? 0 : 1);
    }

    private static void matrix(BenchEnvironment environment, ResultWriter writer, int repeat, String about)
            throws Exception {
        Map<String, List<Double>> drained = new HashMap<>();
        for (KeySpread keys : KeySpread.values()) {
            for (int workers : WORKERS) {
                Condition condition = Condition.drain(workers, keys, BATCH, DRAIN_COMMANDS.get(keys));
                for (int r = 1; r <= repeat; r++) {
                    RunResult result = record(writer, Drain.run(environment, condition, r), about);
                    if (result.verdict().passed()) {
                        drained.computeIfAbsent(workers + "/" + keys, k -> new ArrayList<>()).add(result.completedPerSecond());
                    }
                }
            }
        }
        for (int batch : new int[] {32, 512}) {
            Condition condition = Condition.drain(4, KeySpread.SPREAD, batch, DRAIN_COMMANDS.get(KeySpread.SPREAD));
            for (int r = 1; r <= repeat; r++) {
                record(writer, Drain.run(environment, condition, r), about);
            }
        }
        for (KeySpread keys : new KeySpread[] {KeySpread.SPREAD, KeySpread.ONE}) {
            for (int workers : WORKERS) {
                List<Double> capacity = drained.get(workers + "/" + keys);
                if (capacity == null) {
                    continue;
                }
                for (double load : LOADS) {
                    double rate = Math.max(1, Math.round(load * Percentiles.median(capacity)));
                    Condition condition = Condition.steady(workers, keys, BATCH, rate, STEADY, WARMUP);
                    for (int r = 1; r <= repeat; r++) {
                        record(writer, Steady.run(environment, condition, r), about);
                    }
                }
            }
        }
    }

    private static RunResult record(ResultWriter writer, RunResult result, String about) throws IOException {
        writer.add(result);
        writer.summary(about);
        System.out.printf("%-36s #%d  %s  passed=%s %s%n", result.condition().label(), result.repetition(),
                result.completedPerSecond() == null ? "-" : String.format("%.0f/s", result.completedPerSecond()),
                result.verdict().passed(), result.note());
        return result;
    }

    private static RunResult measure(BenchEnvironment environment, Condition condition, int repetition) throws Exception {
        return condition.measure().equals("drain") ? Drain.run(environment, condition, repetition)
                : Steady.run(environment, condition, repetition);
    }

    private static Condition single(Map<String, String> o) {
        int workers = Integer.parseInt(o.getOrDefault("workers", "4"));
        KeySpread keys = KeySpread.valueOf(o.getOrDefault("keys", "spread").toUpperCase());
        int batch = Integer.parseInt(o.getOrDefault("batch", String.valueOf(BATCH)));
        if (o.getOrDefault("measure", "drain").equals("drain")) {
            return Condition.drain(workers, keys, batch, Integer.parseInt(o.getOrDefault("commands", "20000")));
        }
        return Condition.steady(workers, keys, batch, Double.parseDouble(o.getOrDefault("rate", "500")),
                Duration.ofSeconds(Long.parseLong(o.getOrDefault("seconds", "30"))),
                Duration.ofSeconds(Long.parseLong(o.getOrDefault("warmup", "5"))));
    }

    private static Map<String, String> options(String[] args) {
        if (args.length == 0 || !(args[0].equals("matrix") || args[0].equals("single"))) {
            throw new IllegalArgumentException("usage: matrix --out DIR | single --measure drain|steady ... --out DIR");
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return options;
    }
}
