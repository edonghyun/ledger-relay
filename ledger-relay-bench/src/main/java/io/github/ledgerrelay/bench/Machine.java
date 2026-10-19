package io.github.ledgerrelay.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Where the numbers were measured. Every result file starts with it. */
final class Machine {

    private Machine() {
    }

    static String describe(BenchEnvironment environment) {
        Runtime runtime = Runtime.getRuntime();
        return String.join("\n",
                "- Host: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch") + ", " + runtime.availableProcessors() + " CPUs visible to the JVM",
                "- JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                        + ", max heap " + runtime.maxMemory() / (1024 * 1024) + " MiB",
                "- Docker: " + command("docker", "info", "--format",
                        "server {{.ServerVersion}}, {{.NCPU}} CPUs, {{.MemTotal}} bytes memory, {{.ContainersRunning}} containers running"),
                "- Images: " + environment.versions(),
                "- Commit: " + command("git", "rev-parse", "--short", "HEAD") + command("git", "status", "--porcelain")
                        .lines().findAny().map(dirty -> " (with uncommitted changes)").orElse(""));
    }

    private static String command(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(10, TimeUnit.SECONDS);
            return out;
        } catch (IOException e) {
            return "unknown (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
