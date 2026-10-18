package io.github.ledgerrelay.tigerbeetle;

import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import java.math.BigInteger;
import java.util.function.Supplier;

/**
 * TB가 권장하는 시간순 128비트 이체 ID({@link UInt128#id()}). 시간순이라 TB의 LSM 트리에 쓰기가 몰리지 않는다. TB는 0과
 * 2^128-1을 ID로 받지 않으므로 그런 값은 내지 않고 거부한다.
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
            throw new IllegalStateException("TB가 받지 않는 이체 ID입니다: " + id);
        }
        return id;
    }
}
