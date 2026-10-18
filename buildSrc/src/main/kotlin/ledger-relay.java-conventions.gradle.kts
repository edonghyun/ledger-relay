// 모든 모듈(라이브러리 넷과 샘플 앱)이 같이 쓰는 설정: Java 21, 테스트 도구, 테스트 실행 규칙
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

group = "io.github.ledgerrelay"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 테스트 컨테이너의 이미지 버전. 버전 목록 한 곳에서 정한다(compose.yaml과 같은지는 ComposeFileTest가 본다)
    systemProperty("ledgerrelay.tigerbeetle.version", libs.findVersion("tigerbeetle").get().requiredVersion)
    systemProperty("ledgerrelay.postgres.version", libs.findVersion("postgres").get().requiredVersion)
    // 테스트 트리를 스펙 목차처럼 위에서 아래로 읽히게 @Order 순서로 돈다(docs/testing.md §1)
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$OrderAnnotation")
    // TB 클라이언트는 timeout이 없어 서버가 죽으면 영원히 기다린다. 매달림을 실패로 바꾸는 안전망과 전체 상한
    systemProperty("junit.jupiter.execution.timeout.default", "2m")
    timeout = Duration.ofMinutes(15)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
