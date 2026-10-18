// Settings for the library modules (core, jdbc, tigerbeetle, spring-boot). Not published to a remote repository yet.
// ./gradlew publishToMavenLocal puts them in ~/.m2, and other projects use them as
// io.github.ledgerrelay:<module>:0.1.0-SNAPSHOT
plugins {
    id("ledger-relay.java-conventions")
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
