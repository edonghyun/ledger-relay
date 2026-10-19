package io.github.ledgerrelay.bench;

import java.util.List;

final class Percentiles {

    private Percentiles() {
    }

    /** Nearest-rank percentile of {@code values}; null when empty. */
    static Long of(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
