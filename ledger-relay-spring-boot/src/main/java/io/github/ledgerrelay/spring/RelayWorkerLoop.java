package io.github.ledgerrelay.spring;

import io.github.ledgerrelay.core.application.CycleReport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs worker cycles on threads. The cycle itself (claim, submit, writing the result) is done by core's
 * {@link io.github.ledgerrelay.core.application.RelayWorker#runOnce()}; this class only handles threads and shutdown.
 *
 * <p>Shutdown: blocks new cycles and waits up to {@code stopTimeout} for running cycles to finish. A cycle waits for
 * the TB reply up to submit-timeout and then writes the results it got, so it usually finishes within that time.
 * Threads still running after that are interrupted and the method returns. Another worker resends their commands with
 * the same transfer id after the lease ends.
 */
public final class RelayWorkerLoop implements SmartLifecycle {

    private static final Log log = LogFactory.getLog(RelayWorkerLoop.class);

    private final Supplier<CycleReport> cycle;
    private final int concurrency;
    private final Duration idleDelay;
    private final Duration errorDelay;
    private final Duration stopTimeout;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    /**
     * @param cycle       one cycle, usually {@code relayWorker::runOnce}
     * @param idleDelay   pause after a cycle that claimed no commands
     * @param errorDelay  pause after a cycle threw an exception
     * @param stopTimeout upper bound on waiting for running cycles at shutdown
     */
    public RelayWorkerLoop(Supplier<CycleReport> cycle, int concurrency, Duration idleDelay, Duration errorDelay,
            Duration stopTimeout) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("worker.concurrency must be at least 1: " + concurrency);
        }
        this.cycle = cycle;
        this.concurrency = concurrency;
        this.idleDelay = idleDelay;
        this.errorDelay = errorDelay;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (int i = 1; i <= concurrency; i++) {
            Thread thread = new Thread(this::loop, "ledger-relay-worker-" + i);
            threads.add(thread);
            thread.start();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        long deadline = System.nanoTime() + stopTimeout.toNanos();
        for (Thread thread : threads) {
            joinUntil(thread, deadline);
        }
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                log.warn(thread.getName() + " did not finish its cycle within " + stopTimeout + "; interrupting it. "
                        + "Its command is resent with the same transfer id after the lease ends");
                thread.interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                CycleReport report = cycle.get();
                if (report.claimed() == 0) {
                    pause(idleDelay);
                }
            } catch (RuntimeException e) {
                // If one failed cycle (a DB disconnect, for example) killed the thread, this process would silently
                // stop processing. Report it, pause, and continue
                log.warn("Worker cycle failed. Running again after " + errorDelay, e);
                pause(errorDelay);
            }
        }
    }

    private void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static void joinUntil(Thread thread, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            thread.join(Duration.ofNanos(remaining));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
