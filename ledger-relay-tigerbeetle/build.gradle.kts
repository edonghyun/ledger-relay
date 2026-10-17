import java.time.Duration

plugins {
    `java-library`
    `java-test-fixtures`
}

group = "io.github.ledgerrelay"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(libs.tigerbeetle.java)
    api(project(":ledger-relay-core"))

    testFixturesApi(libs.tigerbeetle.java)
    testFixturesApi(libs.testcontainers)
    testFixturesApi(libs.testcontainers.toxiproxy)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":ledger-relay-core")))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    // 개별 테스트 2분 timeout(아래)을 빠져나가는 매달림까지 막는 전체 상한
    timeout = Duration.ofMinutes(15)
    systemProperty("ledgerrelay.tigerbeetle.version", libs.versions.tigerbeetle.get())
    // TB 클라이언트는 timeout이 없어 서버가 죽으면 영원히 기다린다(스펙 §1.2). 매달림을 실패로 바꾸는 안전망.
    systemProperty("junit.jupiter.execution.timeout.default", "2m")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
