plugins {
    id("ledger-relay.library-conventions")
    `java-test-fixtures`
}

// main 의존은 core와 Spring JDBC·TX뿐이다. 드라이버·풀·JPA는 앱이 고른다
dependencies {
    api(project(":ledger-relay-core"))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)

    // PostgreSQL 테스트 컨테이너와 스키마. spring-boot 모듈과 샘플 앱의 테스트도 쓴다
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.spring.jdbc)

    testImplementation(testFixtures(project(":ledger-relay-core")))
    testImplementation(libs.archunit)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
    // 앱이 JPA 트랜잭션 매니저를 쓰는 경우
    testImplementation(libs.spring.orm)
    testImplementation(libs.spring.context)
    testImplementation(libs.hibernate.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform {
        // 측정용 테스트는 기본 실행에서 뺀다(:ledger-relay-jdbc:measurement)
        excludeTags("measurement")
    }
}

// 측정용 테스트만 돈다. 판정하지 않고 실행 계획을 표준 출력에 남긴다
tasks.register<Test>("measurement") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("measurement") }
    testLogging { showStandardStreams = true }
}
