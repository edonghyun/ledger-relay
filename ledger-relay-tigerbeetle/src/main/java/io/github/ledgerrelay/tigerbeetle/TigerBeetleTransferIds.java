package io.github.ledgerrelay.tigerbeetle;

import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import java.math.BigInteger;
import java.util.function.Supplier;

/**
 * TB's recommended time-ordered 128-bit transfer id ({@link UInt128#id()}). Because the ids are time-ordered, writes do
 * not scatter across TB's LSM tree. TB does not accept 0 or 2^128-1 as an id, so such values are rejected.
 */
public final class TigerBeetleTransferIds implements TransferIdGenerator {

    private static final BigInteger U128_MAX = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    private final Supplier<BigInteger> source;

    public TigerBeetleTransferIds() {
        this(() -> UInt128.asBigInteger(UInt128.id()));
    }

    TigerBeetleTransferIds(Supplier<BigInteger> source) {
        this.source = source;
    }

    @Override
    public BigInteger next() {
        BigInteger id = source.get();
        if (id.signum() == 0 || id.equals(U128_MAX)) {
            throw new IllegalStateException("TB does not accept this transfer id: " + id);
        }
        return id;
    }
}
