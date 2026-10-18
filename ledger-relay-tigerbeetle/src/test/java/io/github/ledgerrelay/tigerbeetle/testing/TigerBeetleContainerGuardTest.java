package io.github.ledgerrelay.tigerbeetle.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;

class TigerBeetleContainerGuardTest {

    /**
     * Fake server that prints "listening on", then after the given time logs what a TB crash logs and dies with exit
     * 139.
     */
    private static String crashingServer(String secondsAfterListening) {
        return "sleep 2; echo 'info(main): 0: cluster=0: listening on fake'; sleep " + secondsAfterListening
                + "; echo 'thread 1 panic: reached unreachable code'; exit 139";
    }

    /**
     * Fake server that dies right after printing "listening on". The container may already have ended right after
     * start.
     */
    private static String instantlyCrashingServer() {
        return "echo 'info(main): 0: cluster=0: listening on fake'; echo 'thread 1 panic: reached unreachable code'; exit 139";
    }

    /** Fake server that prints "listening on" and stays alive. */
    private static String healthyServer() {
        return "sleep 2; echo 'info(main): 0: cluster=0: listening on fake'; sleep 600";
    }

    @Test
    @DisplayName("TB Docker: with the default seccomp, startup fails and the exception message carries the io_uring error that caused it")
    void failsWithIoUringErrorWithoutUnconfinedSeccomp() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(false)) {
            assertThatThrownBy(tb::start)
                    .isInstanceOf(ContainerLaunchException.class)
                    .hasMessageContaining("io_uring is not available");
        }
    }

    @Test
    @DisplayName("TB crash (O4): when the server dies once right after listening, a new container is started and startup succeeds")
    void startRetriesWhenTheServerDiesRightAfterListening() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(true, List.of(crashingServer("0.2"), healthyServer()))) {
            // when
            tb.start();

            // then
            tb.assertAlive();
            assertThat(tb.allLogs()).as("logs of both attempts")
                    .contains("reached unreachable code").contains("--- TB start attempt 2 ---");
        }
    }

    @Test
    @DisplayName("TB crash (O4): when the server dies as soon as it prints listening, so the Testcontainers startup check sees it first, it is still restarted")
    void aDeathSeenByTheStartupCheckIsRetried() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(true, List.of(instantlyCrashingServer(), healthyServer()))) {
            // when
            tb.start();

            // then
            tb.assertAlive();
        }
    }

    @Test
    @DisplayName("TB crash: when every restart dies right after listening, start() fails with the number of attempts and the panic log")
    void startFailsWhenServerDiesRightAfterListening() {
        try (TigerBeetleContainer tb = new TigerBeetleContainer(true, crashingServer("0.2"))) {
            assertThatThrownBy(tb::start)
                    .isInstanceOf(ContainerLaunchException.class)
                    .hasMessageContaining("3 attempts")
                    .hasMessageContaining("reached unreachable code");
        }
    }

    @Test
    @DisplayName("TB crash: when the server dies after startup, assertAlive() fails at once and shows the panic log — so the next test does not hang for 2 minutes")
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
    @DisplayName("TB client address: localhost becomes 127.0.0.1")
    void localhostBecomesLoopbackIpv4() {
        assertThat(TigerBeetleContainer.ipv4("localhost")).isEqualTo("127.0.0.1");
    }
}
