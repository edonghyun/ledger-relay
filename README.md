# ledger-relay

PostgreSQL에 업무 데이터를 두고 TigerBeetle(TB)에 돈의 이동을 기록하는 Java/Spring 애플리케이션을 위한 라이브러리다. 원장 명령을 업무 데이터와 **같은 PG 트랜잭션**으로 접수하고, **순서 키 단위로 원장 결과를 기준 삼아** 순서대로 TB에 제출한다. 그 결과를 업무 상태로 분류해 앱의 후속 처리와 함께 원자적으로 기록한다.

PG와 TB는 한 트랜잭션으로 묶을 수 없다. TB 응답은 유실될 수 있고(클라이언트에 timeout이 없다), 일시적 실패로 끝난 ID는 다시 쓸 수 없으며, 확정이 예약보다 먼저 도착하면 확정 ID가 소진된다. 일반 Outbox는 "전달 성공"으로 순서를 매긴다. 이 모듈은 "원장 결과"로 다음 명령을 보낼지, 기다릴지, 건너뛸지 정한다.

> 상태: 개발 중(v0.1 전). 공개 API는 아직 바뀔 수 있다.

## 지금 되는 것

| 모듈 | 내용 |
|---|---|
| `ledger-relay-core` | 명령 모델과 검증, 내용 해시, 상태 전이표·결과 분류표, 순서 게이트, 서비스(접수, claim, 결과 쓰기, 멈춤·포기), 워커 한 바퀴(`RelayWorker`: 묶음 제출, in-flight 상한, submit-timeout). JDK만 쓴다 |
| `ledger-relay-jdbc` | PostgreSQL 저장소(`JdbcCommandStore`)와 Spring 트랜잭션 어댑터(`JdbcTransactions`), 스키마 `schema.sql`. DataSource·JPA 트랜잭션 매니저에서 앱 트랜잭션에 합류한다 |
| `ledger-relay-tigerbeetle` | TB 0.17.9 Java 클라이언트 어댑터(`TigerBeetleLedgerClient`)와 Testcontainers 픽스처 |

## 아직 안 되는 것

- Spring Boot 자동 설정, 워커 스레드·주기·정상 종료, Micrometer 지표, health, 운영자 공개 포트(멈춘 명령 목록·포기), 운영용 이체 ID 생성기(3b 단계)
- 샘플 앱(`examples/wallet-app`), 장애 실험(E1~E10), 보관 작업

## 문서

- `docs/failure-modes.md`: 막은 장애마다 깨지는 것, 재현 테스트, 구조, 확인(대조군)
- `docs/testing.md`: 테스트 작성 규칙(테스트 트리 = 스펙 목차, given/when/then, 효과 이름 assert)
- `docs/development.md`: 개발 환경, 테스트 컨테이너, 알려진 문제, 운영 메모

## 빌드와 테스트

JDK 21이 필요하다. PG와 TB 테스트는 Docker(Testcontainers)를 쓴다.

```bash
./gradlew test                          # 전체
./gradlew :ledger-relay-core:test       # Docker 없이 도는 core만
```

TB 컨테이너는 `seccomp=unconfined`로 띄운다(io_uring). 기동 직후의 간헐 panic과 그 완화는 `docs/failure-modes.md` F26에 있다.
