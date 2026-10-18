// 라이브러리 모듈(core, jdbc, tigerbeetle, spring-boot)의 설정. 아직 원격 저장소에 배포하지 않는다.
// ./gradlew publishToMavenLocal 로 ~/.m2에 올려 다른 프로젝트에서 io.github.ledgerrelay:<모듈>:0.1.0-SNAPSHOT 으로 쓴다
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
