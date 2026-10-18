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
 * 워커 바퀴를 스레드로 반복한다(스펙 §5.5). 바퀴 자체(claim, 제출, 결과 쓰기)는 core의
 * {@link io.github.ledgerrelay.core.application.RelayWorker#runOnce()}가 하고, 여기는 스레드와 종료만 맡는다.
 *
 * <p>종료: 새 바퀴를 막고, 돌고 있는 바퀴가 끝나기를 {@code stopTimeout}까지 기다린다. 바퀴는 TB 응답을 submit-timeout까지
 * 기다린 뒤 받은 결과를 쓰므로 보통 그 안에 끝난다. 그래도 남은 스레드는 인터럽트하고 돌아온다. 그 명령은 lease가 끝난 뒤
 * 다른 워커가 같은 이체 ID로 다시 보낸다.
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
     * @param cycle       한 바퀴. 보통 {@code relayWorker::runOnce}
     * @param idleDelay   잡은 명령이 없던 바퀴 뒤에 쉬는 시간
     * @param errorDelay  바퀴가 예외를 던진 뒤 쉬는 시간
     * @param stopTimeout 종료 때 돌고 있는 바퀴를 기다리는 상한
     */
    public RelayWorkerLoop(Supplier<CycleReport> cycle, int concurrency, Duration idleDelay, Duration errorDelay,
            Duration stopTimeout) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("worker.concurrency는 1 이상이어야 합니다: " + concurrency);
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
                log.warn(thread.getName() + "가 " + stopTimeout + " 안에 바퀴를 끝내지 못해 인터럽트한다. 그 명령은 lease가 "
                        + "끝난 뒤 같은 이체 ID로 다시 보낸다");
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
                // 한 바퀴의 실패(DB 끊김 등)로 스레드가 죽으면 그 프로세스의 처리가 조용히 멈춘다. 알리고 쉬었다가 계속한다
                log.warn("워커 바퀴가 실패했다. " + errorDelay + " 뒤 다시 돈다", e);
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
