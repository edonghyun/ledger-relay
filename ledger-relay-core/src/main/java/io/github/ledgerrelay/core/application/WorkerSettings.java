package io.github.ledgerrelay.core.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings for one worker cycle ({@code worker.*}). The defaults live in the spring-boot module's settings
 * ({@code LedgerRelayProperties}).
 *
 * @param batchSize     the maximum number of commands to claim per cycle, and the maximum transfers per TB request
 * @param maxInFlight   the number of transfers that may be in flight to TB at once across the whole process
 * @param lease         how long a claimed command is held. It must be at least twice submitTimeout, and should be sized
 *                      to batchSize so it also covers writing the results of one cycle (one transaction per command,
 *                      handler included)
 * @param submitTimeout the upper limit on waiting for a TB response. After it, the commands are put back
 * @param pollTimeout   the upper limit on waiting for in-flight slots. If none are taken, that cycle does not claim
 */
public record WorkerSettings(int batchSize, int maxInFlight, Duration lease, Duration submitTimeout,
        Duration pollTimeout) {

    /** The maximum number of events in one TB request (TB 0.17, 1 MiB message). */
    public static final int TB_MAX_BATCH = 8189;

    public WorkerSettings {
        if (batchSize < 1 || batchSize > TB_MAX_BATCH) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + TB_MAX_BATCH + ": " + batchSize);
        }
        if (maxInFlight < batchSize) {
            throw new IllegalArgumentException("maxInFlight (" + maxInFlight + ") cannot be smaller than batchSize (" + batchSize
                    + "). Otherwise the worker can never take its slots");
        }
        requirePositive("lease", lease);
        requirePositive("submitTimeout", submitTimeout);
        requirePositive("pollTimeout", pollTimeout);
        // The lease starts at the claim and must cover recording the submission, waiting for the response and writing
        // each
        // command's result. If it is short, another worker claims and sends the same command during the wait, and the
        // rejection TB remembers is masked by IdAlreadyFailed
        if (lease.compareTo(submitTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("lease (" + lease + ") must be at least twice submitTimeout (" + submitTimeout
                    + "). The lease must not expire while waiting for the response and writing the result");
        }
    }

    private static void requirePositive(String field, Duration value) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be longer than 0: " + value);
        }
    }
}
