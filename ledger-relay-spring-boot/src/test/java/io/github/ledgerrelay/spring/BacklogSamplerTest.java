package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.CommandBacklog;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Backlog sampler — reads the backlog on its own thread so scrapes and workers never wait for the DB")
class BacklogSamplerTest {

    @Test
    @DisplayName("A sample is taken every interval")
    void aSampleIsTakenEveryInterval() throws Exception {
        // given
        AtomicInteger calls = new AtomicInteger();
        try (BacklogSampler sampler = new BacklogSampler(() -> backlog(calls.incrementAndGet()), Duration.ofMillis(50))) {

            // when
            sampler.start();

            // then
            assertBecomesTrue(() -> sampler.latest().map(CommandBacklog::pending).orElse(0L) >= 3, Duration.ofSeconds(2),
                    "a third sample");
        }
    }

    @Test
    @DisplayName("A failed sample keeps the last value and is counted — a DB blip does not blank the dashboard")
    void aFailedSampleKeepsTheLastValue() throws Exception {
        // given: the first read works, every later read fails
        AtomicInteger calls = new AtomicInteger();
        try (BacklogSampler sampler = new BacklogSampler(() -> {
            if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("the DB is down");
            }
            return backlog(7);
        }, Duration.ofMillis(20))) {

            // when
            sampler.start();
            assertBecomesTrue(() -> sampler.failures() >= 2, Duration.ofSeconds(2), "two failed samples");

            // then
            assertThat(sampler.latest()).as("last sample").map(CommandBacklog::pending).contains(7L);
        }
    }

    @Test
    @DisplayName("A slow DB does not make readers wait — the gauge returns the last sample at once")
    void aSlowSourceDoesNotBlockReaders() throws Exception {
        // given
        try (BacklogSampler sampler = new BacklogSampler(() -> {
            sleep(Duration.ofSeconds(5));
            return backlog(1);
        }, Duration.ofMillis(10))) {
            sampler.start();

            // when
            long start = System.nanoTime();
            Optional<CommandBacklog> latest = sampler.latest();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            // then
            assertThat(latest).as("sample before the first read finished").isEmpty();
            assertThat(took).as("time to read the gauge").isLessThan(Duration.ofMillis(10));
        }
    }

    @Test
    @DisplayName("Closing stops the sampling thread")
    void closeStopsTheThread() throws Exception {
        // given
        AtomicInteger calls = new AtomicInteger();
        BacklogSampler sampler = new BacklogSampler(() -> backlog(calls.incrementAndGet()), Duration.ofMillis(10));
        sampler.start();
        assertBecomesTrue(() -> calls.get() >= 1, Duration.ofSeconds(2), "a first sample");

        // when
        sampler.close();
        int afterClose = calls.get();
        sleep(Duration.ofMillis(100));

        // then
        assertThat(calls.get()).as("samples taken after closing").isLessThanOrEqualTo(afterClose + 1);
    }

    private static CommandBacklog backlog(long pending) {
        return new CommandBacklog(pending, 0, 0, Optional.empty(), Instant.parse("2026-10-19T00:00:00Z"));
    }

    private static void assertBecomesTrue(BooleanSupplier condition, Duration limit, String what) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(10));
        }
        throw new AssertionError("Not reached within " + limit + ": " + what);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
