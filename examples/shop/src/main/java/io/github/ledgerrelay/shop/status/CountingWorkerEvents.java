package io.github.ledgerrelay.shop.status;

import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counts the commands this instance sent to TB. The Workers box in the UI shows the count per instance, which makes it
 * visible that the two workers split the work. The library adds this listener next to its own logging and metrics.
 */
@Component
class CountingWorkerEvents implements WorkerEvents {

    private final AtomicLong submitted = new AtomicLong();

    long submitted() {
        return submitted.get();
    }

    @Override
    public void submitted(int batchSize) {
        submitted.addAndGet(batchSize);
    }
}
