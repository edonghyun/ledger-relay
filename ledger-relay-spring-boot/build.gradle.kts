plugins {
    id("ledger-relay.library-conventions")
}

// 조립(자동 설정), 워커 루프, health만 둔다. 업무 판단은 core에 있다
dependencies {
    api(project(":ledger-relay-core"))
    implementation(project(":ledger-relay-jdbc"))
    // 앱이 com.tigerbeetle.Client 빈을 직접 줄 수 있게 TB 클라이언트 타입을 노출한다
    api(project(":ledger-relay-tigerbeetle"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    // health 기여자는 앱이 actuator(spring-boot-health)를 쓸 때만 켜진다
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
