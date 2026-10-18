package io.github.ledgerrelay.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("이체 ID 생성기 — TB의 시간순 ID, 0과 최댓값은 거부 (03b)")
class TigerBeetleTransferIdsTest {

    static final BigInteger U128_MAX = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    @Test
    @DisplayName("낸 ID는 모두 다르고 앞의 것보다 크다")
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
            assertThat(issued.get(i)).as("%d번째 ID", i).isGreaterThan(issued.get(i - 1));
        }
    }

    @Test
    @DisplayName("0을 내려 하면 거부한다 — TB는 0을 ID로 받지 않는다")
    void zeroIsRefused() {
        // given
        TigerBeetleTransferIds ids = new TigerBeetleTransferIds(() -> BigInteger.ZERO);

        // then
        assertThatThrownBy(ids::next).as("0").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("2^128-1을 내려 하면 거부한다 — TB는 최댓값을 ID로 받지 않는다")
    void theMaximumIsRefused() {
        // given
        TigerBeetleTransferIds ids = new TigerBeetleTransferIds(() -> U128_MAX);

        // then
        assertThatThrownBy(ids::next).as("최댓값").isInstanceOf(IllegalStateException.class);
    }
}
