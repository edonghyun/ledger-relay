package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.application.CycleReport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@DisplayName("Worker loop — threads repeat cycles, and shutdown finishes within its limit")
class RelayWorkerLoopTest {

    static final Duration SHORT = Duration.ofMillis(10);

    RelayWorkerLoop loop;

    @AfterEach
    void stopTheLoop() {
        if (loop != null && loop.isRunning()) {
            loop.stop();
        }
    }

    @Nested
    @Order(1)
    @DisplayName("While running")
    class Running {

        @Test
        @DisplayName("A cycle that throws does not kill the thread, and the next cycle runs")
        void aLoopSurvivesAThrowingCycle() throws InterruptedException {
            // given: the first two cycles throw as if the DB connection were lost
            AtomicInteger cycles = new AtomicInteger();
            CountDownLatch thirdCycle = new CountDownLatch(1);
            loop = new RelayWorkerLoop(() -> {
                int n = cycles.incrementAndGet();
                if (n <= 2) {
                    throw new IllegalStateException("DB connection lost");
                }
                thirdCycle.countDown();
                return CycleReport.IDLE;
            }, 1, SHORT, SHORT, Duration.ofSeconds(1));

            // when
            loop.start();

            // then
            assertThat(thirdCycle.await(2, TimeUnit.SECONDS)).as("third cycle ran").isTrue();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When asked to stop")
    class Stopping {

        @Test
        @DisplayName("Stopping waits for the running cycle to finish before returning — so it writes the results it got")
        void stoppingWaitsForTheRunningCycle() throws Exception {
            // given: a cycle is writing its result
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finishWriting = new CountDownLatch(1);
            AtomicBoolean written = new AtomicBoolean();
            loop = new RelayWorkerLoop(once(() -> {
                entered.countDown();
                awaitQuietly(finishWriting);
                written.set(true);
                return new CycleReport(1, 1, 1, 0);
            }), 1, SHORT, SHORT, Duration.ofSeconds(5));
            loop.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).as("cycle started").isTrue();

            // when
            CompletableFuture<Void> stopping = CompletableFuture.runAsync(loop::stop);
            Thread.sleep(200);
            boolean returnedEarly = stopping.isDone();
            finishWriting.countDown();
            stopping.get(2, TimeUnit.SECONDS);

            // then
            assertThat(returnedEarly).as("returned before the cycle finished").isFalse();
            assertThat(written).as("cycle wrote all its results").isTrue();
            assertThat(loop.isRunning()).as("running after stop").isFalse();
        }

        @Test
        @DisplayName("Stopping returns after the limit even if the cycle does not finish — shutting the app down while TB is unreachable")
        void stoppingWhileTheLedgerIsUnreachableEndsInTime() throws InterruptedException {
            // given: the cycle never finishes (TB does not reply)
            CountDownLatch entered = new CountDownLatch(1);
            loop = new RelayWorkerLoop(() -> {
                entered.countDown();
                awaitQuietly(new CountDownLatch(1));
                return CycleReport.IDLE;
            }, 1, SHORT, SHORT, Duration.ofMillis(500));
            loop.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).as("cycle started").isTrue();

            // when
            long started = System.nanoTime();
            loop.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            // then
            assertThat(took).as("time taken to stop").isLessThan(Duration.ofMillis(1500));
            assertThat(loop.isRunning()).as("running after stop").isFalse();
        }
    }

    /** Works once, then has nothing to do. */
    private static Supplier<CycleReport> once(Supplier<CycleReport> first) {
        AtomicBoolean done = new AtomicBoolean();
        return () -> done.compareAndSet(false, true) ? first.get() : CycleReport.IDLE;
    }

    /** Returns as soon as it is interrupted (the interrupt flag stays set). */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
