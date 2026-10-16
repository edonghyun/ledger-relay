package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 개발 모드 단일 레플리카 TigerBeetle 컨테이너.
 *
 * <p>TB는 io_uring을 쓰므로 Docker 기본 seccomp 프로필에서는 기동하지 못한다(스펙 §12).
 * format과 start를 한 컨테이너에서 실행하려고 엔트리포인트를 셸로 바꾼다.
 */
public final class TigerBeetleContainer extends GenericContainer<TigerBeetleContainer> {

    public static final int PORT = 3000;
    public static final String NETWORK_ALIAS = "tigerbeetle";

    static final String VERSION_PROPERTY = "ledgerrelay.tigerbeetle.version";
    private static final String DATA_FILE = "/tmp/0_0.tigerbeetle";

    public TigerBeetleContainer() {
        this(true);
    }

    TigerBeetleContainer(boolean unconfinedSeccomp) {
        super(DockerImageName.parse(imageTag()));
        withExposedPorts(PORT);
        withCreateContainerCmdModifier(cmd -> {
            cmd.withEntrypoint("/bin/sh", "-c");
            if (unconfinedSeccomp) {
                cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
            }
        });
        withCommand(new String[] {startScript()});
        waitingFor(Wait.forLogMessage(".*listening on.*", 1).withStartupTimeout(Duration.ofSeconds(120)));
    }

    static String imageTag() {
        String version = System.getProperty(VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    VERSION_PROPERTY + " 시스템 프로퍼티가 없습니다. Gradle test 태스크가 버전 카탈로그 값을 넘겨야 합니다.");
        }
        return "ghcr.io/tigerbeetle/tigerbeetle:" + version;
    }

    private static String startScript() {
        return "/tigerbeetle format --cluster=0 --replica=0 --replica-count=1 --development " + DATA_FILE
                + " && exec /tigerbeetle start --addresses=0.0.0.0:" + PORT + " --development " + DATA_FILE;
    }

    /** TB 클라이언트가 해석할 수 있는 {@code IPv4:포트} 주소. */
    public String replicaAddress() {
        return ipv4(getHost()) + ":" + getMappedPort(PORT);
    }

    /** 새 클라이언트(새 세션). 호출자가 닫아야 한다. */
    public Client newClient() {
        return new Client(UInt128.asBytes(0), new String[] {replicaAddress()});
    }

    static String ipv4(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .filter(Inet4Address.class::isInstance)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("IPv4 주소가 없습니다: " + host))
                    .getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("호스트를 해석할 수 없습니다: " + host, e);
        }
    }
}
