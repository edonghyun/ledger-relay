package io.github.ledgerrelay.tigerbeetle.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

class ProxiedTigerBeetleCleanupTest {

    private static int runningTigerBeetleContainers() {
        return DockerClientFactory.instance().client().listContainersCmd()
                .withAncestorFilter(List.of(TigerBeetleContainer.imageTag()))
                .exec()
                .size();
    }

    @Test
    @DisplayName("자원 정리: Toxiproxy 기동이 실패하면 이미 띄운 TB 컨테이너를 정리하고 원래 예외를 그대로 전한다")
    void stopsTigerBeetleWhenToxiproxyFailsToStart() {
        int before = runningTigerBeetleContainers();

        assertThatThrownBy(() -> ProxiedTigerBeetle.start(network -> new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.13.0") {
                    @Override
                    public void start() {
                        throw new IllegalStateException("toxiproxy 기동 실패(테스트)");
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("toxiproxy 기동 실패(테스트)");

        assertThat(runningTigerBeetleContainers()).isEqualTo(before);
    }
}
