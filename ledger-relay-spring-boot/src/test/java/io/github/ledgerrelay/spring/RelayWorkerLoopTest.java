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

@DisplayName("워커 루프 — 스레드가 바퀴를 반복하고, 종료는 상한 안에 끝난다 (스펙 §5.5, 03b)")
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
    @DisplayName("돌다가")
    class Running {

        @Test
        @DisplayName("한 바퀴가 예외를 던져도 스레드가 죽지 않고 다음 바퀴를 돈다")
        void aLoopSurvivesAThrowingCycle() throws InterruptedException {
            // given: 처음 두 바퀴는 DB가 끊긴 것처럼 던진다
            AtomicInteger cycles = new AtomicInteger();
            CountDownLatch thirdCycle = new CountDownLatch(1);
            loop = new RelayWorkerLoop(() -> {
                int n = cycles.incrementAndGet();
                if (n <= 2) {
                    throw new IllegalStateException("DB 연결이 끊겼다");
                }
                thirdCycle.countDown();
                return CycleReport.IDLE;
            }, 1, SHORT, SHORT, Duration.ofSeconds(1));

            // when
            loop.start();

            // then
            assertThat(thirdCycle.await(2, TimeUnit.SECONDS)).as("세 번째 바퀴가 돌았는가").isTrue();
        }
    }

    @Nested
    @Order(2)
    @DisplayName("멈추라고 하면")
    class Stopping {

        @Test
        @DisplayName("돌고 있는 바퀴가 끝날 때까지 기다렸다가 돌아온다 — 받은 결과를 쓰고 끝나게")
        void stoppingWaitsForTheRunningCycle() throws Exception {
            // given: 한 바퀴가 결과를 쓰는 중이다
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
            assertThat(entered.await(2, TimeUnit.SECONDS)).as("바퀴가 시작됐는가").isTrue();

            // when
            CompletableFuture<Void> stopping = CompletableFuture.runAsync(loop::stop);
            Thread.sleep(200);
            boolean returnedEarly = stopping.isDone();
            finishWriting.countDown();
            stopping.get(2, TimeUnit.SECONDS);

            // then
            assertThat(returnedEarly).as("바퀴가 끝나기 전에 돌아왔는가").isFalse();
            assertThat(written).as("바퀴가 결과를 다 썼는가").isTrue();
            assertThat(loop.isRunning()).as("멈춘 뒤 도는가").isFalse();
        }

        @Test
        @DisplayName("바퀴가 끝나지 않아도 상한이 지나면 돌아온다 — TB가 끊긴 채 앱을 끄는 경우")
        void stoppingWhileTheLedgerIsUnreachableEndsInTime() throws InterruptedException {
            // given: 바퀴가 끝나지 않는다(응답 없는 TB)
            CountDownLatch entered = new CountDownLatch(1);
            loop = new RelayWorkerLoop(() -> {
                entered.countDown();
                awaitQuietly(new CountDownLatch(1));
                return CycleReport.IDLE;
            }, 1, SHORT, SHORT, Duration.ofMillis(500));
            loop.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).as("바퀴가 시작됐는가").isTrue();

            // when
            long started = System.nanoTime();
            loop.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            // then
            assertThat(took).as("멈추는 데 걸린 시간").isLessThan(Duration.ofMillis(1500));
            assertThat(loop.isRunning()).as("멈춘 뒤 도는가").isFalse();
        }
    }

    /** 처음 한 번만 일하고, 그 뒤로는 할 일이 없다. */
    private static Supplier<CycleReport> once(Supplier<CycleReport> first) {
        AtomicBoolean done = new AtomicBoolean();
        return () -> done.compareAndSet(false, true) ? first.get() : CycleReport.IDLE;
    }

    /** 인터럽트되면 그대로 끝난다(인터럽트 표시는 남긴다). */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
