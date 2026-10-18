package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The app creates the TB client itself. The library auto-configuration uses the {@link Client} the app provides.
 *
 * <p>The TB client accepts only {@code IPv4:port} and does not resolve host names. Inside compose, the Toxiproxy in
 * front of TB is reachable only by name ({@code toxiproxy}), so the name is resolved to IPv4 at startup.
 */
@Configuration(proxyBeanMethods = false)
class TigerBeetleClientConfig {

    @Bean(destroyMethod = "close")
    Client tigerBeetleClient(ShopProperties properties) {
        return new Client(UInt128.asBytes(0), new String[] {toIpv4Address(properties.tigerbeetle().address())});
    }

    static String toIpv4Address(String hostAndPort) {
        int colon = hostAndPort.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("The TB address must be host:port: " + hostAndPort);
        }
        String host = hostAndPort.substring(0, colon);
        try {
            String ipv4 = Arrays.stream(InetAddress.getAllByName(host)).filter(Inet4Address.class::isInstance).findFirst()
                    .orElseThrow(() -> new IllegalStateException(host + " has no IPv4 address")).getHostAddress();
            return ipv4 + hostAndPort.substring(colon);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot resolve the host of the TB address: " + host, e);
        }
    }
}
