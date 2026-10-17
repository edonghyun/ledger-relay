package io.github.ledgerrelay.tigerbeetle.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;

class TigerBeetleContainerGuardTest {

    /** "listening on"을 찍은 뒤 지정한 시간 후 TB 크래시와 같은 로그를 남기고 exit 139로 죽는 가짜 서버. */
    private static String crashingServer(String secondsAfterListening) {
        return "sleep 2; echo 'info(main): 0: cluster=0: listening on fake'; sleep " + secondsAfterListening
                + "; echo 'thread 1 panic: reached unreachable code'; exit 139";
    }

    /** "listening on"을 찍고 계속 살아 있는 가짜 서버. */
    private static String healthyServer() {
        return "sleep 2; echo 'info(main): 0: cluster=0: listening on fake'; sleep 600";
    }

    @Test
    @DisplayName("§12 TB Docker: seccomp가 기본값이면 기동에 실패하고, 예외 메시지에 원인 io_uring 오류가 담긴다")
    void failsWithIoUringErrorWithoutUnconfinedSeccomp() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(false)) {
            assertThatThrownBy(tb::start)
                    .isInstanceOf(ContainerLaunchException.class)
                    .hasMessageContaining("io_uring is not available");
        }
    }

    @Test
    @DisplayName("§12 TB 크래시(O4): listening 직후 한 번 죽어도 새 컨테이너로 다시 띄워 기동에 성공한다")
    void startRetriesWhenTheServerDiesRightAfterListening() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(true, List.of(crashingServer("0.2"), healthyServer()))) {
            // when
            tb.start();

            // then
            tb.assertAlive();
        }
    }

    @Test
    @DisplayName("§12 TB 크래시: 다시 띄워도 매번 listening 직후 죽으면 start()가 실패하고, 시도 횟수와 panic 로그가 담긴다")
    void startFailsWhenServerDiesRightAfterListening() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(true, crashingServer("0.2"))) {
            assertThatThrownBy(tb::start)
                    .isInstanceOf(ContainerLaunchException.class)
                    .hasMessageContaining("3번 시도")
                    .hasMessageContaining("reached unreachable code");
        }
    }

    @Test
    @DisplayName("§12 TB 크래시: 기동 후 서버가 죽으면 assertAlive()가 즉시 실패하고 panic 로그를 보여준다 — 다음 테스트가 2분씩 매달리지 않게")
    void assertAliveFailsAfterServerDies() throws Exception {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(true, crashingServer("3"))) {
            tb.start();
            tb.assertAlive();

            Thread.sleep(4_000);

            assertThatThrownBy(tb::assertAlive)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reached unreachable code");
        }
    }

    @Test
    @DisplayName("§12 TB 클라이언트 주소: localhost는 127.0.0.1로 바뀐다")
    void localhostBecomesLoopbackIpv4() {
        assertThat(TigerBeetleContainer.ipv4("localhost")).isEqualTo("127.0.0.1");
    }
}
