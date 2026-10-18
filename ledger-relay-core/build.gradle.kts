plugins {
    id("ledger-relay.library-conventions")
    `java-test-fixtures`
}

// No main dependencies: core uses only the JDK. ArchitectureTest enforces the direction
dependencies {
    // In-memory store and contract tests (core.testing). The jdbc module runs the same contracts against PostgreSQL
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
    testImplementation(libs.archunit)
}
