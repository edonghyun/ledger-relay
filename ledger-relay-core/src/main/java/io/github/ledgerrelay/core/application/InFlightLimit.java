package io.github.ledgerrelay.core.application;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The cap on the number of transfers in flight to TB at once in the process. Workers share one instance. One slot per
 * transfer. The slots of a sent request come back when that request actually finishes. If TB stalls, slots do not come
 * back and claiming stops. This is the backpressure.
 */
public final class InFlightLimit {

    private final Semaphore slots;
    private final int capacity;

    public InFlightLimit(int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("in-flight slots must be at least 1: " + slots);
        }
        this.slots = new Semaphore(slots, true);
        this.capacity = slots;
    }

    /** Takes n slots at once. If it cannot take them all within the wait limit, it takes none and returns false. */
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

    /** The slot count set at construction. */
    public int capacity() {
        return capacity;
    }

    public int available() {
        return slots.availablePermits();
    }

    /** The slots taken right now. */
    public int inUse() {
        return capacity() - available();
    }
}
