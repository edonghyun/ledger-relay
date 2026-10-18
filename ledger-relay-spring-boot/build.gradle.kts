import java.time.Duration

plugins {
    `java-library`
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

// 조립(자동 설정), 워커 루프, health만 둔다. 업무 판단은 core에 있다(스펙 §7).
dependencies {
    api(project(":ledger-relay-core"))
    implementation(project(":ledger-relay-jdbc"))
    implementation(project(":ledger-relay-tigerbeetle"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    // health 기여자는 앱이 actuator(spring-boot-health)를 쓸 때만 켜진다
    compileOnly(libs.spring.boot.health)

    testImplementation(testFixtures(project(":ledger-relay-core")))
    testImplementation(testFixtures(project(":ledger-relay-tigerbeetle")))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.health)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    timeout = Duration.ofMinutes(15)
    systemProperty("ledgerrelay.tigerbeetle.version", libs.versions.tigerbeetle.get())
    systemProperty("junit.jupiter.execution.timeout.default", "2m")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
