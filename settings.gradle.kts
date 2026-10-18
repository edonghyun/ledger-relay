plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ledger-relay"

include("ledger-relay-core")
include("ledger-relay-tigerbeetle")
include("ledger-relay-jdbc")
include("ledger-relay-spring-boot")

// Sample shop. The library modules do not depend on this project
include(":examples-shop")
project(":examples-shop").projectDir = file("examples/shop")
