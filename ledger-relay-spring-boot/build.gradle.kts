plugins {
    id("ledger-relay.library-conventions")
}

// Only wiring (auto-configuration), the worker loop and health live here. Business decisions live in core
dependencies {
    api(project(":ledger-relay-core"))
    implementation(project(":ledger-relay-jdbc"))
    // Exposes the TB client types so the app can provide its own com.tigerbeetle.Client bean
    api(project(":ledger-relay-tigerbeetle"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    // The health contributor turns on only when the app uses actuator (spring-boot-health)
    compileOnly(libs.spring.boot.health)

    testImplementation(testFixtures(project(":ledger-relay-core")))
    testImplementation(testFixtures(project(":ledger-relay-jdbc")))
    testImplementation(testFixtures(project(":ledger-relay-tigerbeetle")))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.health)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
    testRuntimeOnly(libs.slf4j.simple)
}
