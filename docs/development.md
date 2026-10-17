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

## PostgreSQL 테스트 컨테이너 (ledger-relay-jdbc)

- 이미지 `postgres:18.6`. JVM에 컨테이너 하나를 처음 쓸 때 띄우고, `schema.sql`을 한 번 적용한다(`PostgresStorage`).
- 커넥션 풀(HikariCP)은 풀 기본 격리 수준마다 하나씩 만들어 재사용한다. 테스트마다 `TRUNCATE ledger_command, ledger_ordering_key RESTART IDENTITY`로 비운다. JPA 테스트는 `orders`도 비운다.
- 실행: `./gradlew :ledger-relay-jdbc:test`. 상속한 계약의 장은 클래스 이름이 `CommandStoreContract$…`라서 `--tests '*JdbcCommandStoreTest*장이름*'` 필터에 걸리지 않는다. 클래스 전체를 돌리고 결과에서 장을 본다.

### 앱이 JPA를 쓸 때

- `JdbcTransactions(transactionManager, dataSource)`와 `JdbcCommandStore(dataSource)`에는 트랜잭션 매니저가 쥔 것과 같은 DataSource 객체를 준다. 다르면 접수가 거부된다(F18).
- `JpaTransactionManager`에 같은 DataSource를 알려 주고(`setDataSource`), JPA dialect는 `HibernateJpaDialect`(Spring이 Hibernate에 쓰는 기본)를 바꾸지 않는다. 이 dialect가 JDBC 커넥션을 트랜잭션에 노출해야 `JdbcClient`가 같은 트랜잭션에 합류한다(F16).
- `DefaultJpaDialect`는 격리 수준 지정을 거부한다(`InvalidIsolationLevelException`). 워커 단계는 READ COMMITTED를 명시하므로(P2b-16) 이 dialect에서는 시작하지 못한다. `JtaTransactionManager`도 `allowCustomIsolationLevels=true`가 아니면 같다.

## 알려진 문제

- **TB 서버 크래시 (원인 미상)**: 2026-10-16 전체 테스트 5회 중 2회(리뷰어 실행 포함), 한 실행 안에서 세 번째로 띄운 TB 컨테이너가 기동 직후(`listening on` 뒤 2~130ms) `thread 1 panic: reached unreachable code`(exit 139)로 종료됐다. OOM은 아니었다. 새 컨테이너에 바로 요청을 보내는 단독 재현 시도 12회에서는 재현되지 않았다.
- TB 클라이언트에는 timeout이 없어서, 서버가 죽으면 요청이 영원히 대기한다. 픽스처의 대응은 다음과 같다.
  - `TigerBeetleContainer`는 컨테이너 로그를 항상 보관한다. 기동 실패는 로그 끝부분을 담은 `ContainerLaunchException`으로 다시 던진다.
  - `listening on` 뒤 1초 동안 살아 있는지 확인한다. 이 구간 안에 죽으면 그 자리에서 실패한다.
  - TB를 쓰는 테스트 클래스는 `@BeforeEach`에서 `assertAlive()`를 부른다. 서버가 도중에 죽어도 그 클래스의 남은 테스트가 2분씩 매달리지 않고 panic 로그와 함께 바로 실패한다.
  - 안전망으로 개별 테스트에는 2분, Gradle 테스트 태스크 전체에는 15분 상한을 둔다.
