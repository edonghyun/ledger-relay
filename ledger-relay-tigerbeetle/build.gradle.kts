plugins {
    id("ledger-relay.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(libs.tigerbeetle.java)
    api(project(":ledger-relay-core"))

    // TB test container (optionally behind Toxiproxy) and a test ledger.
    // Tests in the spring-boot module and the sample app use them too
    testFixturesApi(libs.tigerbeetle.java)
    testFixturesApi(libs.testcontainers)
    testFixturesApi(libs.testcontainers.toxiproxy)

    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":ledger-relay-core")))
    testRuntimeOnly(libs.slf4j.simple)
}
