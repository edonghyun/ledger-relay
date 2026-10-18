package io.github.ledgerrelay.core.spi;

import java.math.BigInteger;

/**
 * Issues one TB transfer id at acceptance. It must be a u128 other than 0 and 2^128-1, and never issues the same value
 * twice.
 */
@FunctionalInterface
public interface TransferIdGenerator {
    BigInteger next();
}
