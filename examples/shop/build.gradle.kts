import java.time.Duration

plugins {
    id("ledger-relay.java-conventions")
    alias(libs.plugins.spring.boot)
}

// 끝-끝 테스트: 실제 compose.yaml을 띄운다. 이미지 빌드가 있어 느리므로 test와 나누고 check에 건다
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

    testImplementation(testFixtures(project(":ledger-relay-jdbc")))
    testImplementation(testFixtures(project(":ledger-relay-tigerbeetle")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}

val composeTestTask = tasks.register<Test>("composeTest") {
    description = "docker compose up 한 번으로 뜬 샘플 쇼핑몰에 장면 1~5를 돌린다"
    group = "verification"
    testClassesDirs = composeTest.output.classesDirs
    classpath = composeTest.runtimeClasspath
    // 이미지 빌드(Gradle 안의 Gradle)가 처음에는 몇 분 걸린다
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
