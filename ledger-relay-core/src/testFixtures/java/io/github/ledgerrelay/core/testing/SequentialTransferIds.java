package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 1부터 차례로 이체 ID를 낸다. 테스트에서 이체 ID를 손으로 쓸 수 있게 한다. */
public final class SequentialTransferIds implements TransferIdGenerator {

    private final AtomicLong next = new AtomicLong(1);

    @Override
    public BigInteger next() {
        return BigInteger.valueOf(next.getAndIncrement());
    }
}
