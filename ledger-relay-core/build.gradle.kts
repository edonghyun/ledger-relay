plugins {
    id("ledger-relay.library-conventions")
    `java-test-fixtures`
}

// main 의존성 없음: core는 JDK만 쓴다. 방향은 ArchitectureTest가 지킨다
dependencies {
    // 메모리 저장소와 계약 테스트(core.testing). jdbc 모듈이 같은 계약을 PostgreSQL로 돌린다
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
    testImplementation(libs.archunit)
}
