package io.github.ledgerrelay.spring;

import java.math.BigInteger;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code ledger-relay.*} 설정. */
@ConfigurationProperties("ledger-relay")
public record LedgerRelayProperties(
        @DefaultValue Tigerbeetle tigerbeetle,
        @DefaultValue Worker worker,
        @DefaultValue Backoff backoff) {

    /**
     * @param clusterId TB 클러스터 ID
     * @param addresses TB 레플리카 주소들, 쉼표로 구분. 앱이 {@code com.tigerbeetle.Client} 빈을 주면 쓰지 않는다
     */
    public record Tigerbeetle(@DefaultValue("0") BigInteger clusterId, @DefaultValue("3000") String addresses) {
    }

    /**
     * @param enabled     끄면 워커 없이 접수·조회만 한다
     * @param concurrency 이 프로세스에서 바퀴를 도는 스레드 수
     * @param idleDelay   잡은 명령이 없던 바퀴 뒤에 쉬는 시간
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("4") int concurrency,
            @DefaultValue("128") int batchSize,
            @DefaultValue("1024") int maxInFlight,
            @DefaultValue("30s") Duration leaseDuration,
            @DefaultValue("5s") Duration submitTimeout,
            @DefaultValue("1s") Duration pollTimeout,
            @DefaultValue("100ms") Duration idleDelay) {
    }

    public record Backoff(@DefaultValue("200ms") Duration initial, @DefaultValue("30s") Duration max) {
    }
}
