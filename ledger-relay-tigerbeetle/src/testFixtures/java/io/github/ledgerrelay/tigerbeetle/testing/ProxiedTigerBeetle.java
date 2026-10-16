package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Toxiproxy를 앞에 둔 TigerBeetle. 클라이언트는 프록시로만 접속하므로, 프록시를 끊으면
 * "요청은 보냈지만 응답이 없는" 상황을 결정적으로 만들 수 있다(스펙 §9 E2의 기반).
 */
public final class ProxiedTigerBeetle implements AutoCloseable {

    private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.13.0";
    private static final int CONTROL_PORT = 8474;
    private static final int PROXY_PORT = 8666;

    private final Network network;
    private final TigerBeetleContainer tigerBeetle;
    private final ToxiproxyContainer toxiproxy;
    private final Proxy proxy;

    private ProxiedTigerBeetle(Network network, TigerBeetleContainer tigerBeetle, ToxiproxyContainer toxiproxy,
            Proxy proxy) {
        this.network = network;
        this.tigerBeetle = tigerBeetle;
        this.toxiproxy = toxiproxy;
        this.proxy = proxy;
    }

    public static ProxiedTigerBeetle start() {
        Network network = Network.newNetwork();
        // 네트워크 별칭은 사용자 정의 네트워크에서만 유효하므로 여기서 붙인다(TigerBeetleContainer 단독 사용 시에는 없음)
        TigerBeetleContainer tigerBeetle = new TigerBeetleContainer()
                .withNetwork(network)
                .withNetworkAliases(TigerBeetleContainer.NETWORK_ALIAS);
        ToxiproxyContainer toxiproxy = new ToxiproxyContainer(TOXIPROXY_IMAGE)
                .withNetwork(network)
                .withExposedPorts(CONTROL_PORT, PROXY_PORT);
        tigerBeetle.start();
        toxiproxy.start();
        try {
            ToxiproxyClient control = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getMappedPort(CONTROL_PORT));
            Proxy proxy = control.createProxy("tigerbeetle", "0.0.0.0:" + PROXY_PORT,
                    TigerBeetleContainer.NETWORK_ALIAS + ":" + TigerBeetleContainer.PORT);
            return new ProxiedTigerBeetle(network, tigerBeetle, toxiproxy, proxy);
        } catch (IOException e) {
            toxiproxy.stop();
            tigerBeetle.stop();
            network.close();
            throw new UncheckedIOException("Toxiproxy 프록시 생성 실패", e);
        }
    }

    public String clientAddress() {
        return TigerBeetleContainer.ipv4(toxiproxy.getHost()) + ":" + toxiproxy.getMappedPort(PROXY_PORT);
    }

    public Client newClient() {
        return new Client(UInt128.asBytes(0), new String[] {clientAddress()});
    }

    /** 프록시 뒤 TB 서버가 살아 있는지 확인한다. {@link TigerBeetleContainer#assertAlive()} 참고. */
    public void assertAlive() {
        tigerBeetle.assertAlive();
    }

    /** 프록시를 끊는다. 기존 연결도 닫힌다. */
    public void cutConnection() throws IOException {
        proxy.disable();
    }

    /** 프록시를 다시 연다. */
    public void restoreConnection() throws IOException {
        proxy.enable();
    }

    @Override
    public void close() {
        toxiproxy.stop();
        tigerBeetle.stop();
        network.close();
    }
}
