package io.github.ledgerrelay.tigerbeetle.testing;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 개발 모드 단일 레플리카 TigerBeetle 컨테이너.
 *
 * <p>TB는 io_uring을 쓰므로 Docker 기본 seccomp 프로필에서는 기동하지 못한다(스펙 §12).
 * format과 start를 한 컨테이너에서 실행하려고 엔트리포인트를 셸로 바꾼다.
 *
 * <p>TB 클라이언트는 timeout이 없어 서버가 죽으면 요청이 영원히 대기한다(스펙 §1.2). 그래서 이 픽스처는
 * 컨테이너 로그를 항상 보관하고, 기동 직후 크래시(docs/development.md "알려진 문제")와 기동 후 사망을
 * 로그와 함께 즉시 실패로 드러낸다.
 */
public final class TigerBeetleContainer extends GenericContainer<TigerBeetleContainer> {

    public static final int PORT = 3000;
    public static final String NETWORK_ALIAS = "tigerbeetle";

    static final String VERSION_PROPERTY = "ledgerrelay.tigerbeetle.version";
    private static final String DATA_FILE = "/tmp/0_0.tigerbeetle";
    /** 관찰된 크래시는 "listening on" 뒤 2~130ms 안에 일어났다. 그보다 넉넉한 안정 확인 구간. */
    private static final Duration STABILITY_WINDOW = Duration.ofSeconds(1);
    private static final int LOG_TAIL_CHARS = 2_000;

    private final ToStringConsumer logs = new ToStringConsumer();

    public TigerBeetleContainer() {
        this(true);
    }

    TigerBeetleContainer(boolean unconfinedSeccomp) {
        this(unconfinedSeccomp, startScript());
    }

    /** 테스트 전용: 시작 스크립트를 바꿔 크래시 같은 서버 동작을 흉내 낸다. */
    TigerBeetleContainer(boolean unconfinedSeccomp, String script) {
        super(DockerImageName.parse(imageTag()));
        withExposedPorts(PORT);
        withCreateContainerCmdModifier(cmd -> {
            cmd.withEntrypoint("/bin/sh", "-c");
            if (unconfinedSeccomp) {
                cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
            }
        });
        withCommand(new String[] {script});
        withLogConsumer(logs);
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

    /** 기동 실패를 컨테이너 로그 끝부분과 함께 다시 던진다. 원인(io_uring, panic)이 메시지에 남는다. */
    @Override
    public void start() {
        try {
            super.start();
        } catch (RuntimeException e) {
            throw new ContainerLaunchException("TigerBeetle 컨테이너 기동 실패. 로그 끝부분:\n" + logTail(), e);
        }
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        try {
            Thread.sleep(STABILITY_WINDOW.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("안정 확인 중 인터럽트", e);
        }
        if (!isRunning()) {
            throw new IllegalStateException("TigerBeetle가 listening 직후 종료되었습니다");
        }
    }

    /**
     * 서버가 아직 살아 있는지 확인한다. 죽었다면 로그와 함께 실패한다. 테스트의 {@code @BeforeEach}에서 불러,
     * 죽은 서버에 보낸 요청이 timeout까지 매달리는 일이 테스트마다 반복되지 않게 한다.
     */
    public void assertAlive() {
        if (!isRunning()) {
            throw new IllegalStateException("TigerBeetle 컨테이너가 종료되었습니다. 로그 끝부분:\n" + logTail());
        }
    }

    private String logTail() {
        String all = logs.toUtf8String();
        return all.length() <= LOG_TAIL_CHARS ? all : all.substring(all.length() - LOG_TAIL_CHARS);
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
