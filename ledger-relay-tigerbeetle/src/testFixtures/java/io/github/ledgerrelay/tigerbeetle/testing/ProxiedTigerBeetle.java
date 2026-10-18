package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * TigerBeetle behind Toxiproxy. The client connects only through the proxy, so cutting the proxy produces the situation
 * "the request was sent but no reply arrives" deterministically (foundation).
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
        return start(network -> new ToxiproxyContainer(TOXIPROXY_IMAGE));
    }

    /** Test only: replaces how the Toxiproxy container is created, to verify the startup failure path. */
    static ProxiedTigerBeetle start(Function<Network, ToxiproxyContainer> toxiproxyFactory) {
        Network network = Network.newNetwork();
        // Network aliases work only on a user-defined network, so they are added here (none when TigerBeetleContainer
        // is used alone)
        // The client connects only through the proxy, so the TB port is not published on the host (removes the cause
        // path of F26)
        TigerBeetleContainer tigerBeetle = new TigerBeetleContainer()
                .withoutPublishedPort()
                .withNetwork(network)
                .withNetworkAliases(TigerBeetleContainer.NETWORK_ALIAS);
        ToxiproxyContainer toxiproxy = toxiproxyFactory.apply(network)
                .withNetwork(network)
                .withExposedPorts(CONTROL_PORT, PROXY_PORT);
        try {
            tigerBeetle.start();
            toxiproxy.start();
            ToxiproxyClient control = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getMappedPort(CONTROL_PORT));
            Proxy proxy = control.createProxy("tigerbeetle", "0.0.0.0:" + PROXY_PORT,
                    TigerBeetleContainer.NETWORK_ALIAS + ":" + TigerBeetleContainer.PORT);
            return new ProxiedTigerBeetle(network, tigerBeetle, toxiproxy, proxy);
        } catch (RuntimeException | IOException e) {
            // Whatever step fails, clean up what was already started. stop() on a container that never started does
            // nothing.
            toxiproxy.stop();
            tigerBeetle.stop();
            network.close();
            if (e instanceof IOException io) {
                throw new UncheckedIOException("Failed to create the Toxiproxy proxy", io);
            }
            throw (RuntimeException) e;
        }
    }

    public String clientAddress() {
        return TigerBeetleContainer.ipv4(toxiproxy.getHost()) + ":" + toxiproxy.getMappedPort(PROXY_PORT);
    }

    /**
     * Address of the Toxiproxy admin API ({@code http://IPv4:port}). The proxy is named {@code tigerbeetle}. Used when
     * the app cuts and restores the connection itself.
     */
    public String controlUrl() {
        return "http://" + TigerBeetleContainer.ipv4(toxiproxy.getHost()) + ":" + toxiproxy.getMappedPort(CONTROL_PORT);
    }

    public Client newClient() {
        return new Client(UInt128.asBytes(0), new String[] {clientAddress()});
    }

    /** Checks that the TB server behind the proxy is alive. See {@link TigerBeetleContainer#assertAlive()}. */
    public void assertAlive() {
        tigerBeetle.assertAlive();
    }

    /** Cuts the proxy. Existing connections are closed too. */
    public void cutConnection() throws IOException {
        proxy.disable();
    }

    /** Opens the proxy again. */
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
