# ledger-relay

PostgreSQL에 업무 데이터를 두고 TigerBeetle(TB)에 돈의 이동을 기록하는 Java/Spring 애플리케이션을 위한 라이브러리다. 원장 명령을 업무 데이터와 **같은 PG 트랜잭션**으로 접수하고, **순서 키 단위로 원장 결과를 기준 삼아** 순서대로 TB에 제출한다. 그 결과를 업무 상태로 분류해 앱의 후속 처리와 함께 원자적으로 기록한다.

PG와 TB는 한 트랜잭션으로 묶을 수 없다. TB 응답은 유실될 수 있고(클라이언트에 timeout이 없다), 일시적 실패로 끝난 ID는 다시 쓸 수 없으며, 확정이 예약보다 먼저 도착하면 확정 ID가 소진된다. 일반 Outbox는 "전달 성공"으로 순서를 매긴다. 이 모듈은 "원장 결과"로 다음 명령을 보낼지, 기다릴지, 건너뛸지 정한다.

> 상태: 개발 중(v0.1 전). 공개 API는 아직 바뀔 수 있다.

## 만든 방식

이 저장소의 코드, 테스트, 문서는 **Claude Code(Anthropic의 AI 코딩 도구)가 작성했다.** 나(Donghyun Lee)는 무엇을 만들지와 설계 방향을 정하고, 단계마다 설계와 구현 계획을 승인하고, 결과를 검토했다. 커밋마다 붙은 `Co-Authored-By: Claude` 줄이 그 기록이다.

단계마다 같은 순서로 진행했다.

1. 설계 문서를 쓰고 승인한다.
2. 구현 계획을 쓰고 승인한다.
3. 테스트를 먼저 쓰고, 실패를 확인한 뒤 구현한다(TDD).
4. 새 리뷰어가 브랜치 전체를 리뷰하고, 찾은 결함을 재현 테스트와 함께 고친다.

장애를 막은 변경마다 재현 테스트와 대조군 결과를 `docs/failure-modes.md`에 남겼다. 대조군은 그 수리를 되돌렸을 때 테스트가 실제로 실패하는지 확인한 것이다.

## 샘플 쇼핑몰로 보기

준비물은 Docker뿐이다(JDK, Node 필요 없음). Docker에 메모리를 3GB 이상 준다(TigerBeetle만 1.5GB쯤 쓴다). 저장소 루트에서:

```bash
docker compose up --build        # 처음에는 이미지 빌드로 몇 분 걸린다
```

브라우저로 <http://localhost:8090>을 연다(포트를 바꾸려면 `SHOP_PORT=9000 docker compose up --build`). 끌 때는 `docker compose down`. 데모는 매번 빈 DB와 빈 원장에서 시작한다(Ctrl-C로 멈췄다 `up`하면 그대로 이어진다).

구성은 PostgreSQL, TigerBeetle, 둘 사이의 Toxiproxy(끊기 스위치), 같은 이미지로 띄운 앱 둘(`shop-a`는 화면과 워커, `shop-b`는 워커)이다. 화면의 숫자는 모두 PG와 TB의 실제 상태에서 나온다. 통화는 태국 바트(฿)이고, 결제는 카드처럼 **승인(예약) → 출고 때 매입(확정)** 이다.

| 장면 | 하는 것 | 보이는 것 |
|---|---|---|
| ① 정상 결제 | Store에서 `결제하기` → 주문의 `Ship` | `authorized`(구매자 돈이 묶임) → `paid`(가맹점으로 이동). 오른쪽 타임라인에 단계별 시각, 이체 ID, TB 전송 횟수, 잔액 |
| ② TB 장애 | Scenarios에서 TigerBeetle 스위치를 끄고 몇 번 결제 → 다시 켬 | 결제는 바로 접수되고 backlog가 쌓이며 TB 점이 빨강. 켜면 몇 초 안에 backlog가 비고 아래 Consistency는 초록 그대로 |
| ③ 멈춤과 운영자 | Scenarios의 `Inject bad post` → Operator에서 `Abandon` | 그 주문의 확정만 멈추고 다른 주문은 흐른다. 포기하면 해제가 뒤따라 주문은 `cancelled`, 잔액은 원래대로 |
| ④ 멱등 | Scenarios의 `Double click` | 같은 Idempotency-Key 요청 둘 → 주문 하나(201과 200), 예약 하나 |
| ⑤ 취소 | 결제 승인된 주문의 `Cancel` | 예약 해제, 묶였던 돈이 풀림 |

`Burst ×50`은 주문 50건을 한꺼번에 넣는다. Store의 Workers 칸에서 두 인스턴스가 나눠 보내는 것이 보인다. 이 장면들은 끝-끝 테스트(`./gradlew :examples-shop:composeTest`)가 실제 `compose.yaml`로 그대로 돈다.

## 지금 되는 것

| 모듈 | 내용 |
|---|---|
| `ledger-relay-core` | 명령 모델과 검증, 내용 해시, 상태 전이표·결과 분류표, 순서 게이트, 서비스(접수, claim, 결과 쓰기, 멈춤·포기), 워커 한 바퀴(`RelayWorker`: 묶음 제출, in-flight 상한, submit-timeout). JDK만 쓴다 |
| `ledger-relay-jdbc` | PostgreSQL 저장소(`JdbcCommandStore`)와 Spring 트랜잭션 어댑터(`JdbcTransactions`), 스키마 `schema.sql`. DataSource·JPA 트랜잭션 매니저에서 앱 트랜잭션에 합류한다 |
| `ledger-relay-tigerbeetle` | TB 0.17.9 Java 클라이언트 어댑터(`TigerBeetleLedgerClient`: 제출, 대사용 조회), 시간순 이체 ID 생성기(`TigerBeetleTransferIds`), Testcontainers 픽스처 |
| `ledger-relay-spring-boot` | Spring Boot 4.0 자동 설정, 워커 루프와 정상 종료, health(TB가 끊겨도 UP), 운영자 포트 빈 |

운영자 포트 `LedgerRelayOperator`: 멈춘 명령 목록(이유·시각), 포기, PG와 TB의 대사.

샘플 앱 `examples/shop`(Gradle 프로젝트 `:examples-shop`)은 라이브러리를 쓰는 쪽의 본보기다. 라이브러리 모듈은 이것에 의존하지 않는다.

## 앱에서 쓰기 (Spring Boot)

1. 의존성 `io.github.ledgerrelay:ledger-relay-spring-boot`를 더하고, `schema.sql`(jdbc jar의 `io/github/ledgerrelay/jdbc/schema.sql`)을 마이그레이션 도구로 적용한다. 라이브러리는 스키마를 자동 적용하지 않는다.
2. 설정:

```yaml
ledger-relay:
  tigerbeetle: { cluster-id: 0, addresses: "127.0.0.1:3000" }   # 또는 com.tigerbeetle.Client 빈
  worker: { concurrency: 4, batch-size: 128, max-in-flight: 1024, lease-duration: 30s, submit-timeout: 5s }
  backoff: { initial: 200ms, max: 30s }
```

3. 명령이 끝났을 때 앱의 데이터에 결과를 반영하는 `LedgerOutcomeHandler` 빈을 하나 등록한다. 핸들러는 결과 쓰기와 같은 트랜잭션에서 돈다. 핸들러 안에서 다시 접수할 때는 **앱의 행을 먼저 고친 뒤 접수한다**(`docs/development.md` "트랜잭션과 잠금").
4. 앱 트랜잭션 안에서 `LedgerRelay.submit(...)`으로 접수한다. 업무 데이터와 함께 커밋되거나 함께 롤백된다.

## 아직 안 되는 것

- Micrometer 지표와 대시보드(3c 단계)
- 장애 실험(E1~E10), 보관 작업

## 문서

- `docs/failure-modes.md`: 막은 장애마다 깨지는 것, 재현 테스트, 구조, 확인(대조군)
- `docs/testing.md`: 테스트 작성 규칙(테스트 트리 = 스펙 목차, given/when/then, 효과 이름 assert)
- `docs/development.md`: 개발 환경, 테스트 컨테이너, 알려진 문제, 운영 메모

## 빌드와 테스트

JDK 21이 필요하다. PG와 TB 테스트는 Docker(Testcontainers)를 쓴다.

```bash
./gradlew test                          # 전체(빠른 테스트)
./gradlew :ledger-relay-core:test       # Docker 없이 도는 core만
./gradlew check                         # 위에 더해 샘플 앱 끝-끝 테스트(compose.yaml로 이미지 빌드부터)
```

TB 컨테이너는 `seccomp=unconfined`로 띄운다(io_uring). 기동 직후의 간헐 panic과 그 완화는 `docs/failure-modes.md` F26에 있다.

## 라이선스

[Apache License 2.0](LICENSE)
