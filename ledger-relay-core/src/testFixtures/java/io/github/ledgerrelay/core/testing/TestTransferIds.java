package io.github.ledgerrelay.core.testing;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TB transfer ids for rows that contract tests insert directly into the store. Each command needs its own id (the
 * schema's UNIQUE, F31).
 */
public final class TestTransferIds {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    private TestTransferIds() {
    }

    /**
     * order-1 is 100 + seq (tests refer to 101 and 102 directly). Other keys are spaced 1000 apart by key number
     * (order-2 seq 1 → 1101).
     */
    public static BigInteger transferIdFor(String orderingKey, long seq) {
        Matcher number = TRAILING_NUMBER.matcher(orderingKey);
        long key = number.find() ? Long.parseLong(number.group(1)) : (orderingKey.hashCode() & 0xFFFF) + 10_000;
        return BigInteger.valueOf(100 + seq + 1000 * (key - 1));
    }
}
