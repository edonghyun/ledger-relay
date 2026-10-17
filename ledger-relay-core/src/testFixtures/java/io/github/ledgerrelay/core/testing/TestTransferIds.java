package io.github.ledgerrelay.core.testing;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 계약 테스트가 저장소에 직접 넣는 행의 TB 이체 ID. 이체 ID는 명령마다 달라야 한다(스키마의 UNIQUE, F31). */
public final class TestTransferIds {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    private TestTransferIds() {
    }

    /** order-1은 100 + seq다(테스트가 101, 102를 직접 참조한다). 다른 키는 키 번호마다 1000씩 띄운다(order-2의 seq 1 → 1101). */
    public static BigInteger transferIdFor(String orderingKey, long seq) {
        Matcher number = TRAILING_NUMBER.matcher(orderingKey);
        long key = number.find() ? Long.parseLong(number.group(1)) : (orderingKey.hashCode() & 0xFFFF) + 10_000;
        return BigInteger.valueOf(100 + seq + 1000 * (key - 1));
    }
}
