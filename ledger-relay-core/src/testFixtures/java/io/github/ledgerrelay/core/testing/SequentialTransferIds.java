package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Issues transfer ids in sequence from 1, so tests can write transfer ids by hand. */
public final class SequentialTransferIds implements TransferIdGenerator {

    private final AtomicLong next = new AtomicLong(1);

    @Override
    public BigInteger next() {
        return BigInteger.valueOf(next.getAndIncrement());
    }
}
