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

- `JdbcTransactions(transactionManager, dataSource)`, `JdbcOrderingKeys(dataSource)`, `JdbcCommandStore(dataSource)`에는 트랜잭션 매니저가 쥔 것과 같은 DataSource 객체를 준다. 다르면 접수가 거부된다(F18).
- `JpaTransactionManager`에 같은 DataSource를 알려 주고(`setDataSource`), JPA dialect는 `HibernateJpaDialect`(Spring이 Hibernate에 쓰는 기본)를 바꾸지 않는다. 이 dialect가 JDBC 커넥션을 트랜잭션에 노출해야 `JdbcClient`가 같은 트랜잭션에 합류한다(F16).
- `DefaultJpaDialect`는 격리 수준 지정을 거부한다(`InvalidIsolationLevelException`). 워커 단계는 READ COMMITTED를 명시하므로(P2b-16) 이 dialect에서는 시작하지 못한다. JTA(`JtaTransactionManager`)는 지원하지 않는다. JTA에서는 트랜잭션이 시작될 때 DataSource 커넥션이 Spring 트랜잭션에 묶이지 않아, 접수 앞의 확인(F18)이 거부한다. 필요해지면 따로 결정한다.

### 큐 테이블 운영 (02d)

- `ledger_command`의 부분 인덱스는 여섯이다: `ledger_command_unfinished`, `ledger_command_ready`, `ledger_command_one_head`, `ledger_command_finished_head`(02e), `ledger_command_halted`, `ledger_command_waiting`(03b). 상태가 바뀌거나, lease·다음 시도 시각이 바뀌거나(`ready_at`), 맨 앞 표시가 바뀌면(`is_head`) 인덱스 컬럼이 바뀌어 HOT 갱신이 되지 않는다. 죽은 튜플이 작은 인덱스에 빨리 쌓이므로 테이블별 autovacuum을 낮게 잡는다(예: `autovacuum_vacuum_scale_factor = 0.01`, `autovacuum_vacuum_cost_limit` 상향). `fillfactor = 80`을 두면 `markSubmitted` 같은 인덱스 밖 갱신이 같은 페이지에 들어갈 여지가 생긴다. 수치는 아직 재지 않았다.
- 맨 앞 표시(`is_head`)는 넣을 때 키에 맨 앞이 없으면 켜고, 명령이 끝나면 다음 claim이 넘긴다(F32, F33). claim은 이 표시만 보고 후보를 고른다. **DB를 직접 고친 뒤**(행 삭제, 상태 수정, 키 바꾸기)에는 표시가 행과 어긋난다. 어긋나면 그 키가 영원히 후보에서 빠지거나 맨 앞이 아닌 명령이 먼저 나갈 수 있다. 워커를 멈추고, 고친 트랜잭션을 커밋한 뒤, `ledger-relay-jdbc` jar의 `io/github/ledgerrelay/jdbc/rebuild-heads.sql`을 한 트랜잭션에서 실행하고(`psql -1 -f rebuild-heads.sql`) 나서 워커를 다시 켠다. 스크립트는 먼저 `ledger_ordering_key`를 표 잠금하므로 트랜잭션 밖에서는 실행되지 않는다. 잠금은 커밋까지 앱의 접수를 기다리게 한다. 이 SQL은 테스트의 직접 SQL 준비도 쓴다(`PostgresStorage.rebuildHeads`).
- **02e 스키마 변경**: 열 `ledger_command.is_head`, `ledger_command.ready_at`(생성 컬럼)과 인덱스 `ledger_command_ready`, `ledger_command_one_head`, `ledger_command_finished_head`를 더하고 `ledger_command_due`를 지웠다. 0.1 출시 전이라 마이그레이션 파일은 두지 않는다. 기존 개발 DB는 워커를 멈추고 열과 인덱스를 `schema.sql`대로 더한 뒤 `rebuild-heads.sql`을 실행한다.
- **03b 스키마 변경**: 열 `halt_reason`, `halted_at`, 제약 `ledger_command_halt_check`, 인덱스 `ledger_command_halted`, `ledger_command_waiting`을 더했다. 기존 개발 DB는 두 열과 인덱스를 `schema.sql`대로 더하고, 이미 멈춘 행에 `UPDATE ledger_command SET halt_reason = 'MALFORMED_ROW', halted_at = now() WHERE state = 'HALTED'`를 실행한 뒤 제약을 더한다(이유를 알 수 없는 옛 멈춤은 "이상 행"으로 둔다).
- `ledger_ordering_key`의 행은 지우지 않는다. 지우는 작업이 진행 중인 접수와 겹치면 다음 접수의 순번이 1로 돌아가 순서가 뒤집히거나 앱 트랜잭션이 깨진다(02d P2d-11). 행 하나는 수십 바이트다.

### 트랜잭션과 잠금 (02e F33)

트랜잭션마다 잡는 행이다. 잡은 행은 커밋까지 놓지 않는다. 주문 행은 앱의 테이블 행(예: `orders`)이다.

| 트랜잭션 | 여는 쪽 | 잡는 순서 | 그 안에서 앱 코드가 도나 |
|---|---|---|---|
| 접수(`submit`) | 앱 | (앱이 고친 주문 행) → 키 행 → 새 명령 행 → 앞 명령이 끝났으면 그 명령 행(표시를 끈다) | 앱 트랜잭션 그 자체 |
| claim | 워커 | 끝난 맨 앞 명령 행과 그 키 행(쥔 쪽이 있으면 건너뜀) → 다음 미완료 명령 행(표시를 켠다. 맨 앞이 이미 있는 키는 건드리지 않음) → 후보 명령 행(건너뜀) | 아니오 |
| 결과 쓰기(`complete`, `skip`, `abandonHalted`) | 워커, 운영자 | 그 명령 행 → 핸들러가 고치는 주문 행 | 예(핸들러) |
| 제출 기록·되돌리기(`markSubmitted`, `release`) | 워커 | 그 명령 행 | 아니오 |
| `rebuild-heads.sql` | 운영자 | 키 표 전체 → 명령 행들 | 아니오 |

- 핸들러가 접수하지 않는 한, 키 행과 주문 행을 둘 다 잡는 트랜잭션은 앱의 접수뿐이다. 그래서 워커와 앱이 둘을 반대 순서로 잡아 교착할 일이 없다. claim은 표시를 켤 다음 명령 행이 결과를 쓰는 중이어도 기다리지 않는다. 그 키에 이미 맨 앞이 있으면 건드리지 않기 때문이다. 이 성질은 코드 구조가 지킨다. 키 행을 잡는 호출은 `OrderingKeys`에만 있고, 핸들러를 부르는 `WorkerWrites`는 그 포트를 받지 않는다(`ArchitectureTest`). jdbc의 `CommandStore`가 속으로 키 행을 잡지 않는 것은 `KeyLockFreeStoreTest`가 본다.
- **핸들러 안에서 접수하면**(예: 예약이 적용되면 같은 키에 확정을 접수) 그 결과 쓰기 트랜잭션이 키 행을 잡는다. 이때는 주문 행을 먼저 고친 뒤 접수한다. 반대로 접수한 뒤 주문 행을 고치면, 주문 행을 고친 뒤 같은 키에 접수하는 앱 트랜잭션과 교착할 수 있다(PG가 한쪽을 `40P01`로 끊는다). 핸들러가 커밋할 때까지 그 키의 넘기기도 미뤄진다. ArchUnit 규칙은 이 경로를 보지 못한다. 앱의 `LedgerRelay`를 거치기 때문이다.
- 남는 교착은 앱 트랜잭션끼리다. 한 앱 트랜잭션에서 여러 키에 접수하면 키 이름 순서로 접수한다(failure-modes 4절).
- 앱이 접수를 연 채 오래 머물면 그 키의 다음 명령은 그 앱이 커밋할 때까지 나가지 않는다. 다른 키는 영향이 없다.

### Spring Boot로 띄울 때 (03b)

- 워커 루프는 `ledger-relay.worker.concurrency`개 스레드(`ledger-relay-worker-N`)다. 한 바퀴가 예외를 던지면 로그를 남기고 `backoff.initial`만큼 쉰 뒤 계속한다. 스레드가 죽어 처리가 조용히 멈추지 않게 하려는 것이다.
- 종료: 새 바퀴를 막고, 돌고 있는 바퀴를 `lease-duration`까지 기다린다. 바퀴는 TB 응답을 `submit-timeout`까지 기다린 뒤 결과를 쓰므로 보통 그 안에 끝난다. 그래도 남으면 인터럽트한다. 그 명령은 lease가 끝난 뒤 다른 워커가 같은 이체 ID로 다시 보내므로 돈이 두 번 움직이지 않는다.
- 워커 설정(lease ≥ submit-timeout×2, batch-size ≤ 8189, max-in-flight ≥ batch-size)은 워커를 꺼도 기동에서 검사한다.
- health(`spring-boot-health`가 있을 때): TB가 끊겨도 UP이고 세부 `ledger`만 DOWN이다. 접수는 TB 없이도 되므로, health가 DOWN이면 부하 분산기가 접수까지 막는 일이 생긴다. 저장소를 읽을 수 없을 때만 DOWN이다.
  - TB 확인은 health 요청을 붙잡지 않는다. TB 클라이언트에는 ping이 없어 이체 하나를 조회해 보는데, 답을 기다리지 않는다. 마지막으로 끝난 확인이 성공했고 가 있는 확인이 5초 넘게 답을 기다리고 있지 않으면 UP이다. 그래서 health를 드물게 불러도(Kubernetes 기본 10초) 판정이 맞다(F40). 조회는 한 번에 하나만 보낸다(요청마다 쌓이지 않게).
  - 대기 수는 1,000까지만 센다(`countsCapped`). 가장 오래된 대기는 `ledger_command_waiting`의 첫 항목이다. 장애로 대기가 10만 건이어도 health 한 번은 약 2,000페이지(상한에 비례) 안에서 끝난다. 정확한 수는 지표(03c)가 맡는다.
- 종료는 `lease-duration`(기본 30초)까지 기다릴 수 있다. 웹 서버의 graceful shutdown과 합치면 오케스트레이터의 종료 유예(예: Kubernetes 기본 30초)를 넘을 수 있다. 넘어서 강제 종료돼도 lease와 같은 이체 ID 덕분에 돈은 두 번 움직이지 않는다.

### 샘플 쇼핑몰 (03b-2)

- 구성(`compose.yaml`, 저장소 루트): PostgreSQL 18.6, TigerBeetle 0.17.9(`--development --cache-grid=128MiB`, 처음 뜰 때만 format), Toxiproxy 2.13.0, 앱 둘(`shop-a`, `shop-b`, 같은 이미지). 앱은 TB에 Toxiproxy(`toxiproxy:3001`)로만 붙는다. Scenarios의 TB 스위치는 Toxiproxy 관리 API로 그 프록시를 끄고 켠다. Docker 소켓을 앱에 주지 않는다.
- 앱 컨테이너도 `seccomp=unconfined`로 띄운다. TB Java 클라이언트도 io_uring을 쓰기 때문이다(F41). 라이브러리를 쓰는 앱을 컨테이너로 배포할 때도 같다. 운영에서는 io_uring 시스템 호출(`io_uring_setup`, `io_uring_enter`, `io_uring_register`)만 더 허용한 seccomp 프로필이 낫다.
- 호스트에 여는 포트는 화면 하나(`SHOP_PORT`, 기본 8090)다. PG·TB·Toxiproxy는 열지 않는다. TB 포트를 열지 않으므로 F26의 원인 경로도 없다. 그래도 TB가 죽으면 `restart: on-failure`로 다시 뜬다.
- 이미지는 `examples/shop/Dockerfile`이 저장소 전체를 컨텍스트로 Gradle 빌드한다(멀티 스테이지, Gradle 캐시는 BuildKit 캐시 마운트).
- 스키마는 앱이 기동할 때 적용한다(샘플 앱만의 편의. 앱 둘이 함께 떠도 한 번만, F37). 라이브러리는 여전히 자동 적용하지 않는다.
- TB 주소는 앱이 IPv4로 풀어 `Client` 빈을 직접 만든다. TB 클라이언트는 호스트 이름을 받지 않는다(F39). 라이브러리 설정 `ledger-relay.tigerbeetle.addresses`에도 IP를 쓴다.
- 데모 전용 경로(`/api/demo/*`: TB 끊기, 잘못된 확정 넣기, 몰아넣기)는 `demo` 프로필에서만 있다. compose는 이 프로필로 띄운다. "잘못된 확정"은 정상 API로 만들 수 없는 행을 라이브러리 표에 직접 넣는다(`BadPostInjector`).
- 화면의 숫자: 타일과 단계 칸은 `ledger_command`를 질의 하나로 직접 센다. 데모 크기에서는 충분하지만 운영 지표가 아니다. 03c에서 Micrometer로 바꿀 자리다. TB에서 읽는 숫자(잔액, 중복 이체, 대사)는 전용 스레드 하나가 1초마다 모은다. TB 클라이언트에는 timeout이 없어서, 화면 요청이 TB를 직접 부르면 TB가 끊긴 동안 화면이 멈추기 때문이다.
- 재시도 간격 최대를 2초로 줄였다(`ledger-relay.backoff.max`, 라이브러리 기본 30초). 15초 끊었다 이었을 때 대기가 비는 데 기본값으로는 약 40초, 2초로는 약 5초 걸렸다.
- 워커는 인스턴스마다 스레드 2개, 배치 4건이다. 배치가 작아야 몰아넣기 50건을 한 스레드가 다 가져가지 않고 두 인스턴스가 나눠 보내는 것이 보인다.

### DB를 옮길 때

- 덤프·복원이나 다른 클러스터로 옮긴 DB에서는 옮겨 온 행의 `accepted_xact`가 새 클러스터의 트랜잭션 ID와 우연히 같을 수 있다. 그러면 이미 커밋된 예약이 "같은 트랜잭션에서 접수한 예약"으로 보여 정상 확정이 거부된다(O7). 복원 직후, 워커를 켜기 전에 `UPDATE ledger_command SET accepted_xact = '0'`을 실행한다.

## 알려진 문제

- **TB 서버 크래시 (O4 → F26)**: TB 0.17.9는 `listening on` 직후의 짧은 구간에 TCP 접속이 들어오면 `thread 1 panic: reached unreachable code`로 죽는다. Docker Desktop의 포트 공개가 그 구간에 접속을 넣는 것으로 보인다. 메모리 부족은 아니었다. 재현 방법과 수치는 docs/failure-modes.md F26에 있다. 픽스처는 이번 시도가 listening까지 갔다가 죽으면 새 컨테이너로 다시 띄운다(모두 3번 시도, 다시 띄우기는 최대 2번). 다시 띄운 횟수는 테스트 결과 XML의 system-err에서 `O4:`로 센다.
- TB 클라이언트에는 timeout이 없어서, 서버가 죽으면 요청이 영원히 대기한다. 픽스처의 대응은 다음과 같다.
  - `TigerBeetleContainer`는 컨테이너 로그를 항상 보관한다. 기동 실패는 로그 끝부분을 담은 `ContainerLaunchException`으로 다시 던진다.
  - `listening on` 뒤 1초 동안 살아 있는지 확인한다. 이 구간 안에 죽으면 새 컨테이너로 다시 띄운다(F26). 세 번 모두 죽으면 그때 실패한다.
  - TB를 쓰는 테스트 클래스는 `@BeforeEach`에서 `assertAlive()`를 부른다. 서버가 도중에 죽어도 그 클래스의 남은 테스트가 2분씩 매달리지 않고 panic 로그와 함께 바로 실패한다.
  - 안전망으로 개별 테스트에는 2분, Gradle 테스트 태스크 전체에는 15분 상한을 둔다.
