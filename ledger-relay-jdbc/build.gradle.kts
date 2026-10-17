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

// main 의존은 core와 Spring JDBC·TX뿐이다. 드라이버·풀·JPA는 앱이 고른다(스펙 §7).
dependencies {
    api(project(":ledger-relay-core"))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)

    testImplementation(testFixtures(project(":ledger-relay-core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
    testImplementation(libs.testcontainers.postgresql)
    // 앱이 JPA 트랜잭션 매니저를 쓰는 경우(R16)
    testImplementation(libs.spring.orm)
    testImplementation(libs.spring.context)
    testImplementation(libs.hibernate.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    // 개별 테스트 2분 timeout(아래)을 빠져나가는 매달림까지 막는 전체 상한
    timeout = Duration.ofMinutes(15)
    // 잠금을 기다리다 매달린 동시성 테스트를 실패로 바꾸는 안전망
    systemProperty("junit.jupiter.execution.timeout.default", "2m")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
