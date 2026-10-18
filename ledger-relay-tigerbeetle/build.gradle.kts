plugins {
    id("ledger-relay.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(libs.tigerbeetle.java)
    api(project(":ledger-relay-core"))

    // TB 테스트 컨테이너(Toxiproxy 앞단 포함)와 테스트용 원장. spring-boot 모듈과 샘플 앱의 테스트도 쓴다
    testFixturesApi(libs.tigerbeetle.java)
    testFixturesApi(libs.testcontainers)
    testFixturesApi(libs.testcontainers.toxiproxy)

    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":ledger-relay-core")))
    testRuntimeOnly(libs.slf4j.simple)
}
