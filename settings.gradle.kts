plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ledger-relay"

include("ledger-relay-core")
include("ledger-relay-tigerbeetle")
include("ledger-relay-jdbc")
include("ledger-relay-spring-boot")

// 샘플 쇼핑몰(03b §7). 라이브러리 모듈은 이 프로젝트에 의존하지 않는다
include(":examples-shop")
project(":examples-shop").projectDir = file("examples/shop")
