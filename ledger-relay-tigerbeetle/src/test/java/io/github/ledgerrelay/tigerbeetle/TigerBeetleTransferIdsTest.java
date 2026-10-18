package io.github.ledgerrelay.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Transfer id generator — TB's time-ordered ids; 0 and the maximum are rejected")
class TigerBeetleTransferIdsTest {

    static final BigInteger U128_MAX = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    @Test
    @DisplayName("Issued ids are all distinct and each is greater than the previous one")
    void idsAreUniqueAndIncreasing() {
        // given
        TigerBeetleTransferIds ids = new TigerBeetleTransferIds();

        // when
        List<BigInteger> issued = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            issued.add(ids.next());
        }

        // then
        for (int i = 1; i < issued.size(); i++) {
            assertThat(issued.get(i)).as("id #%d", i).isGreaterThan(issued.get(i - 1));
        }
    }

    @Test
    @DisplayName("Issuing 0 is rejected — TB does not accept 0 as an id")
    void zeroIsRefused() {
        // given
        TigerBeetleTransferIds ids = new TigerBeetleTransferIds(() -> BigInteger.ZERO);

        // then
        assertThatThrownBy(ids::next).as("0").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Issuing 2^128-1 is rejected — TB does not accept the maximum as an id")
    void theMaximumIsRefused() {
        // given
        TigerBeetleTransferIds ids = new TigerBeetleTransferIds(() -> U128_MAX);

        // then
        assertThatThrownBy(ids::next).as("maximum").isInstanceOf(IllegalStateException.class);
    }
}
