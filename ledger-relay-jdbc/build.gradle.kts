plugins {
    id("ledger-relay.library-conventions")
    `java-test-fixtures`
}

// main depends only on core and Spring JDBC/TX. The app chooses the driver, the pool and JPA
dependencies {
    api(project(":ledger-relay-core"))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)

    // PostgreSQL test container and schema. The spring-boot module and the sample app tests use them too
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.spring.jdbc)

    testImplementation(testFixtures(project(":ledger-relay-core")))
    testImplementation(libs.archunit)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
    // For apps that use a JPA transaction manager
    testImplementation(libs.spring.orm)
    testImplementation(libs.spring.context)
    testImplementation(libs.hibernate.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform {
        // Measurement tests are excluded from the default run (:ledger-relay-jdbc:measurement)
        excludeTags("measurement")
    }
}

// Runs only the measurement tests. They assert nothing and print query plans to standard output
tasks.register<Test>("measurement") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("measurement") }
    testLogging { showStandardStreams = true }
}
