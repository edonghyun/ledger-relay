# 로컬 개발 환경

## 전제

- macOS arm64. Docker Desktop 실행 중, 메모리 4GiB 이상(TB 컨테이너 1개당 약 1.5GiB).
- arm64 JDK 21: `brew install openjdk@21`
- 모든 Gradle 명령은 이 JDK로 실행한다. x86_64 JDK로 실행하면 `BuildEnvironmentTest`가 실패한다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test
```

## TigerBeetle 테스트 컨테이너

- 이미지 `ghcr.io/tigerbeetle/tigerbeetle`의 태그는 `gradle/libs.versions.toml`의 `tigerbeetle` 값에서 나온다. 클라이언트 jar와 같은 값이다.
- io_uring 때문에 `seccomp=unconfined`가 필요하다. 픽스처가 자동으로 적용한다.
- 개발 모드(`--development`)라서 memlock 설정이 필요 없다. 운영 설정과는 다르다.
- 단일 레플리카만 쓴다. 다중 레플리카 장애 조치는 검증하지 않는다(스펙 N12).

## 알려진 문제

- **TB 서버 크래시 (원인 미상)**: 2026-10-16 전체 테스트 4회 중 1회, 컨테이너가 기동 직후 `thread 1 panic: reached unreachable code`(exit 139)로 종료됐다. OOM은 아니었다. 기동 직후 요청을 반복하는 재현 시도 12회에서는 재현되지 않았다.
- TB 클라이언트에는 timeout이 없다. 서버가 죽으면 요청이 영원히 대기한다. 그래서 테스트 태스크에 `junit.jupiter.execution.timeout.default=2m`을 걸어, 매달림이 스택 트레이스와 함께 실패로 끝나게 했다. 이 실패가 나면 `docker ps -a`로 종료된 TB 컨테이너를 찾아 `docker logs`를 확인한다.
