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
    @DisplayName("Cleanup: when Toxiproxy fails to start, the TB container already started is stopped and the original exception is passed on unchanged")
    void stopsTigerBeetleWhenToxiproxyFailsToStart() {
        int before = runningTigerBeetleContainers();

        assertThatThrownBy(() -> ProxiedTigerBeetle.start(network -> new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.13.0") {
                    @Override
                    public void start() {
                        throw new IllegalStateException("toxiproxy failed to start (test)");
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("toxiproxy failed to start (test)");

        assertThat(runningTigerBeetleContainers()).isEqualTo(before);
    }
}
