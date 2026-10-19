import java.time.Duration

plugins {
    id("ledger-relay.java-conventions")
    alias(libs.plugins.spring.boot)
}

// End-to-end tests: start the real compose.yaml. They build images and are slow, so they are kept out of test and wired into check
val composeTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[composeTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[composeTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(project(":ledger-relay-spring-boot"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.postgresql)
    // /actuator/prometheus for the demo dashboard
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(testFixtures(project(":ledger-relay-jdbc")))
    testImplementation(testFixtures(project(":ledger-relay-tigerbeetle")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}

val composeTestTask = tasks.register<Test>("composeTest") {
    description = "Runs scenarios 1-5 against the sample shop started with a single docker compose up"
    group = "verification"
    testClassesDirs = composeTest.output.classesDirs
    classpath = composeTest.runtimeClasspath
    // The first image build (Gradle inside Gradle) takes a few minutes
    systemProperty("junit.jupiter.execution.timeout.default", "15m")
    timeout = Duration.ofMinutes(30)
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(composeTestTask)
}

tasks.bootJar {
    archiveFileName = "shop.jar"
}
