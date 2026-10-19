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
 * Single-replica TigerBeetle container in development mode, or in the production configuration for measurements
 * ({@link #production()}).
 *
 * <p>TB uses io_uring, so it cannot start under Docker's default seccomp profile.
 * The entrypoint is replaced with a shell so that format and start run in one container.
 *
 * <p>The TB client has no timeout, so when the server dies, requests wait forever. That is why this fixture always
 * keeps the container logs and surfaces a crash right after startup (docs/development.md "Known issues") and a death
 * after startup immediately as a failure, together with the logs.
 */
public final class TigerBeetleContainer extends GenericContainer<TigerBeetleContainer> {

    public static final int PORT = 3000;
    public static final String NETWORK_ALIAS = "tigerbeetle";

    static final String VERSION_PROPERTY = "ledgerrelay.tigerbeetle.version";
    private static final String DATA_FILE = "/tmp/0_0.tigerbeetle";
    /** Observed crashes happened 2–130 ms after "listening on". A stability window with a comfortable margin. */
    private static final Duration STABILITY_WINDOW = Duration.ofSeconds(1);
    private static final int LOG_TAIL_CHARS = 2_000;
    /**
     * Maximum number of times to start a new container when the server dies right after listening (O4). TB 0.17.9
     * panics when a TCP connection arrives in a short window right after listening. Docker Desktop can open a
     * connection to the published port in that window, so the tests cannot prevent it (docs/failure-modes.md F26).
     */
    static final int START_ATTEMPTS = 3;

    private final ToStringConsumer logs = new ToStringConsumer();
    private final List<String> scriptsPerAttempt;
    private final List<int[]> attemptMarks = new java.util.ArrayList<>();

    public TigerBeetleContainer() {
        this(true);
    }

    /**
     * Without {@code --development}. A development replica takes at most 253 transfers per request and answers at most
     * 8 clients; a ninth client gets no reply at all. Measurements need neither limit. Takes about 2.5 GiB of memory.
     */
    public static TigerBeetleContainer production() {
        return new TigerBeetleContainer(true, "/tigerbeetle format --cluster=0 --replica=0 --replica-count=1 "
                + DATA_FILE + " && exec /tigerbeetle start --addresses=0.0.0.0:" + PORT + " --cache-grid=512MiB "
                + DATA_FILE);
    }

    TigerBeetleContainer(boolean unconfinedSeccomp) {
        this(unconfinedSeccomp, startScript());
    }

    /** Test only: replaces the start script to imitate server behavior such as a crash. Restarts reuse the script. */
    TigerBeetleContainer(boolean unconfinedSeccomp, String script) {
        this(unconfinedSeccomp, List.of(script));
    }

    /** Test only: uses a different script per attempt. With more attempts than scripts, the last one is used. */
    TigerBeetleContainer(boolean unconfinedSeccomp, List<String> scriptsPerAttempt) {
        super(DockerImageName.parse(imageTag()));
        this.scriptsPerAttempt = List.copyOf(scriptsPerAttempt);
        withExposedPorts(PORT);
        withCreateContainerCmdModifier(cmd -> {
            cmd.withEntrypoint("/bin/sh", "-c");
            if (unconfinedSeccomp) {
                cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
            }
        });
        withLogConsumer(logs);
        waitingFor(Wait.forLogMessage(".*listening on.*", 1).withStartupTimeout(Duration.ofSeconds(120)));
    }

    static String imageTag() {
        String version = System.getProperty(VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    VERSION_PROPERTY + " system property is missing. The Gradle test task must pass the version "
                            + "catalog value.");
        }
        return "ghcr.io/tigerbeetle/tigerbeetle:" + version;
    }

    private static String startScript() {
        return "/tigerbeetle format --cluster=0 --replica=0 --replica-count=1 --development " + DATA_FILE
                + " && exec /tigerbeetle start --addresses=0.0.0.0:" + PORT + " --development " + DATA_FILE;
    }

    /**
     * Starts the container. If the server dies right after listening, a new container is started, up to
     * {@link #START_ATTEMPTS} times (O4). Other startup failures (io_uring and so on) repeat on restart, so they are
     * thrown at once. Failures are thrown with the tail of the container log, so the cause (io_uring, panic) stays in
     * the message.
     */
    @Override
    public void start() {
        for (int attempt = 1; ; attempt++) {
            withCommand(new String[] {scriptsPerAttempt.get(Math.min(attempt, scriptsPerAttempt.size()) - 1)});
            int attemptStart = logs.toUtf8String().length();
            attemptMarks.add(new int[] {attempt, attemptStart});
            try {
                super.start();
                return;
            } catch (RuntimeException e) {
                // Decide by whether this attempt reached listening, not by which step (the Testcontainers startup
                // check, the log wait, the stability check) saw the death first. Testcontainers has already removed
                // the failed container
                boolean listenedThenDied = listenedSince(attemptStart);
                if (!listenedThenDied || attempt == START_ATTEMPTS) {
                    String tries = listenedThenDied ? attempt + " attempts all exited right after listening. " : "";
                    throw new ContainerLaunchException("TigerBeetle container failed to start. " + tries + "Log tail:\n"
                            + logTail(), e);
                }
                // Logged so that the retry rate can be counted in the test results (system-err in the XML)
                System.err.println("O4: TigerBeetle exited right after listening; starting a new container (attempt "
                        + attempt + ")");
            }
        }
    }

    /** Whether listening appears in this attempt's log. The log arrives separately, so this waits briefly. */
    private boolean listenedSince(int attemptStart) {
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        do {
            if (logs.toUtf8String().substring(attemptStart).contains("listening on")) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        try {
            Thread.sleep(STABILITY_WINDOW.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during the stability check", e);
        }
        if (!isRunning()) {
            throw new DiedRightAfterListening();
        }
    }

    /**
     * Checks that the server is still alive. If it died, fails with the logs. Call it from a test's
     * {@code @BeforeEach} so that requests to a dead server do not hang until timeout in every test.
     */
    public void assertAlive() {
        if (!isRunning()) {
            throw new IllegalStateException("The TigerBeetle container has exited. Log tail:\n" + logTail());
        }
    }

    /**
     * Does not publish the port on the host. Used when connecting only through a proxy on the same Docker network.
     * A connection through the published port causes the panic right after listening (F26), so the port is not
     * published when there is no need.
     */
    public TigerBeetleContainer withoutPublishedPort() {
        setExposedPorts(new java.util.ArrayList<>());
        return this;
    }

    /** Test only: logs of all attempts, with a separator line per attempt. */
    String allLogs() {
        return withAttemptMarks(logs.toUtf8String());
    }

    /**
     * Inserts a {@code --- TB start attempt n ---} separator per attempt, so that after a retry a panic from an earlier
     * attempt is not mistaken for the current one.
     */
    private String withAttemptMarks(String all) {
        StringBuilder marked = new StringBuilder();
        int from = 0;
        for (int[] mark : attemptMarks) {
            int at = Math.min(mark[1], all.length());
            marked.append(all, from, at).append("--- TB start attempt ").append(mark[0]).append(" ---\n");
            from = at;
        }
        return marked.append(all.substring(from)).toString();
    }

    private String logTail() {
        String all = withAttemptMarks(logs.toUtf8String());
        return all.length() <= LOG_TAIL_CHARS ? all : all.substring(all.length() - LOG_TAIL_CHARS);
    }

    /**
     * The server died within the stability window. Whether to restart is decided by this attempt's log, not by this
     * exception.
     */
    private static final class DiedRightAfterListening extends IllegalStateException {
        DiedRightAfterListening() {
            super("TigerBeetle exited right after listening");
        }
    }

    /** An {@code IPv4:port} address the TB client can parse. */
    public String replicaAddress() {
        return ipv4(getHost()) + ":" + getMappedPort(PORT);
    }

    /** A new client (a new session). The caller must close it. */
    public Client newClient() {
        return new Client(UInt128.asBytes(0), new String[] {replicaAddress()});
    }

    static String ipv4(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .filter(Inet4Address.class::isInstance)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No IPv4 address: " + host))
                    .getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot resolve host: " + host, e);
        }
    }
}
