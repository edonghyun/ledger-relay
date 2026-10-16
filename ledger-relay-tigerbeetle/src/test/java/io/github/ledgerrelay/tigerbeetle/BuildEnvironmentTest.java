package io.github.ledgerrelay.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class BuildEnvironmentTest {

    @Test
    @DisplayName("§10 0단계: 테스트 JVM은 Java 21 toolchain으로 실행된다")
    void runsOnJava21Toolchain() {
        assertThat(Runtime.version().feature()).isEqualTo(21);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("§12 로컬 JDK: macOS에서는 arm64 JVM으로 실행된다 — TB JNI aarch64-macos 사용")
    void runsOnArm64JvmOnMac() {
        assertThat(System.getProperty("os.arch")).isEqualTo("aarch64");
    }
}
