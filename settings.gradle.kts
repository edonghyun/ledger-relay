plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ledger-relay"

include("ledger-relay-core")
include("ledger-relay-tigerbeetle")
include("ledger-relay-jdbc")
