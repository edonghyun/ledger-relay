package io.github.ledgerrelay.core.spi;

import java.math.BigInteger;

/** 접수할 때 TB 이체 ID를 하나 낸다(스펙 §5.1-5). 0과 2^128-1이 아닌 u128이어야 하며, 같은 값을 두 번 내지 않는다. */
@FunctionalInterface
public interface TransferIdGenerator {
    BigInteger next();
}
