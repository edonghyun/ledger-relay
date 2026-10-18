package io.github.ledgerrelay.core.application;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 프로세스에서 동시에 TB에 가 있는 이체 수의 상한(스펙 §5.3-2). 워커들이 하나를 나눠 쓴다. 슬롯은 이체 하나에 하나다.
 * 보낸 요청의 슬롯은 그 요청이 실제로 끝날 때 돌아온다. TB가 멈추면 슬롯이 돌아오지 않아 claim이 멈춘다. 이것이 배압이다.
 */
public final class InFlightLimit {

    private final Semaphore slots;
    private final int capacity;

    public InFlightLimit(int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("in-flight 슬롯은 1개 이상이어야 합니다: " + slots);
        }
        this.slots = new Semaphore(slots, true);
        this.capacity = slots;
    }

    /** n개를 한꺼번에 잡는다. 기다림 상한 안에 다 못 잡으면 하나도 잡지 않고 false다. */
    public boolean tryTake(int n, Duration wait) {
        try {
            return slots.tryAcquire(n, wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void giveBack(int n) {
        if (n > 0) {
            slots.release(n);
        }
    }

    /** 만들 때 정한 슬롯 수. */
    public int capacity() {
        return capacity;
    }

    public int available() {
        return slots.availablePermits();
    }
}
