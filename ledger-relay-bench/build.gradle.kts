plugins {
    id("ledger-relay.java-conventions")
    application
}

// Load measurement of the library (docs/benchmarks.md). It assembles core, jdbc and tigerbeetle directly, without
// Spring Boot or HTTP, and starts PostgreSQL and TigerBeetle with the test containers of those modules.
// ./gradlew :ledger-relay-bench:run --args="matrix --out build/bench/<name>"
dependencies {
    implementation(project(":ledger-relay-core"))
    implementation(project(":ledger-relay-jdbc"))
    implementation(project(":ledger-relay-tigerbeetle"))
    implementation(testFixtures(project(":ledger-relay-jdbc")))
    implementation(testFixtures(project(":ledger-relay-tigerbeetle")))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "io.github.ledgerrelay.bench.Bench"
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

tasks.named<JavaExec>("run") {
    // The test containers read their image versions from these properties (ledger-relay.java-conventions)
    systemProperty("ledgerrelay.tigerbeetle.version", catalog.findVersion("tigerbeetle").get().requiredVersion)
    systemProperty("ledgerrelay.postgres.version", catalog.findVersion("postgres").get().requiredVersion)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    workingDir = projectDir
}
