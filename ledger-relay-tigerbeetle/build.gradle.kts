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

    testFixturesApi(libs.tigerbeetle.java)
    testFixturesApi(libs.testcontainers)
    testFixturesApi(libs.testcontainers.toxiproxy)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("ledgerrelay.tigerbeetle.version", libs.versions.tigerbeetle.get())
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
