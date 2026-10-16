package io.github.ledgerrelay.tigerbeetle.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

class TigerBeetleContainerGuardTest {

    @Test
    @DisplayName("§12 TB Docker: seccomp가 기본값이면 기동에 실패하고, 원인 io_uring 오류가 로그에 남는다")
    void failsWithIoUringErrorWithoutUnconfinedSeccomp() {
        // 기동 실패 후에는 컨테이너가 정리될 수 있어 getLogs()에 기대지 않고, 출력을 흘려받아 보관한다
        ToStringConsumer logs = new ToStringConsumer();
        try (TigerBeetleContainer tb = new TigerBeetleContainer(false)
                .withLogConsumer(logs)
                .waitingFor(Wait.forLogMessage(".*listening on.*", 1).withStartupTimeout(Duration.ofSeconds(30)))) {
            assertThatThrownBy(tb::start).isInstanceOf(ContainerLaunchException.class);
            assertThat(logs.toUtf8String()).contains("io_uring is not available");
        }
    }

    @Test
    @DisplayName("§12 TB 클라이언트 주소: localhost는 127.0.0.1로 바뀐다")
    void localhostBecomesLoopbackIpv4() {
        assertThat(TigerBeetleContainer.ipv4("localhost")).isEqualTo("127.0.0.1");
    }
}
