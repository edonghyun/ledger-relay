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
 * TB 클라이언트를 앱이 직접 만든다. 라이브러리 자동 설정은 앱이 준 {@link Client}를 그대로 쓴다.
 *
 * <p>TB 클라이언트는 {@code IPv4:포트}만 받고 호스트 이름을 풀지 않는다. compose 안에서 TB 앞의 Toxiproxy는 이름
 * ({@code toxiproxy})으로만 찾을 수 있으므로 기동할 때 IPv4로 바꾼다.
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
            throw new IllegalArgumentException("TB 주소는 호스트:포트여야 합니다: " + hostAndPort);
        }
        String host = hostAndPort.substring(0, colon);
        try {
            String ipv4 = Arrays.stream(InetAddress.getAllByName(host)).filter(Inet4Address.class::isInstance).findFirst()
                    .orElseThrow(() -> new IllegalStateException(host + "에 IPv4 주소가 없습니다")).getHostAddress();
            return ipv4 + hostAndPort.substring(colon);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("TB 주소의 호스트를 찾을 수 없습니다: " + host, e);
        }
    }
}
