// Settings shared by all modules (the four libraries and the sample app): Java 21, test tools, test run rules
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
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

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Image versions of the test containers, set in one place: the version catalog
    // (ComposeFileTest checks that compose.yaml uses the same ones)
    systemProperty("ledgerrelay.tigerbeetle.version", libs.findVersion("tigerbeetle").get().requiredVersion)
    systemProperty("ledgerrelay.postgres.version", libs.findVersion("postgres").get().requiredVersion)
    // Run in @Order order so the test tree reads top to bottom like the spec's table of contents (docs/testing.md)
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$OrderAnnotation")
    // The TB client has no timeout and waits forever if the server dies. A safety net that turns a hang into a
    // failure, plus an overall limit
    systemProperty("junit.jupiter.execution.timeout.default", "2m")
    timeout = Duration.ofMinutes(15)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
