# 테스트 작성 규칙

ledger-relay의 테스트는 **읽으면 스펙이 되는 문서**다. 테스트 트리를 위에서 아래로 읽으면 이 코드가 무엇을 지키는지 알 수 있어야 한다. 기준 사례는 `~/projects/hexai`이다.

## 1. 테스트 트리 = 스펙 목차

- 테스트 클래스는 대상 하나를 맡는다. `@DisplayName`에는 무엇의 규칙인지와 스펙 근거를 적는다.
- `@Nested` 클래스에는 `@Order(n)`으로 목차 번호를 붙인다. JUnit에는 선언 순서 정렬기가 없어서, 번호가 없으면 결과 트리의 장 순서가 뒤섞인다(core는 `src/test/resources/junit-platform.properties`로 `OrderAnnotation`을 기본값으로 둔다).
- `@Nested` 클래스는 **상황**이다. 이름은 "…한 상태에서", "…하면"으로 끝난다. 그 상황의 공통 전제는 해당 `@Nested`의 `@BeforeEach`에 둔다.
- `@Test`는 **행동과 효과**다. 이름은 "~하면 ~이고, ~한다" 형태의 문장으로 쓴다.
- 스펙 ID(§4.4, G7, P4 …)는 그 규칙의 근거가 되는 가장 바깥 단계에 한 번만 붙인다.

테스트 결과 화면에서는 이렇게 읽혀야 한다.

```
TigerBeetle 0.17.9 결과 규칙 — 스펙 §4.4 분류표의 근거
├─ 500원 결제가 이미 기록된 상태에서
│   ├─ 같은 결제를 다시 보내면 이미 기록된 것으로 인정되고, 돈은 한 번만 움직인다
│   └─ 같은 ID에 금액만 바꿔 보내면 내용 충돌로 거부되고, 처음 금액만 남는다
├─ 잔액이 부족한 지갑에서 결제하면
│   ├─ 잔액 부족으로 거부되고, 돈은 움직이지 않는다
│   ├─ 같은 결제를 다시 보내면 이 ID는 이미 실패한 것으로 거부된다
│   └─ 충전한 뒤에도 같은 ID는 실패로 남고, 새 ID로 보내야 결제된다
└─ 존재하지 않는 계좌에서 출금하면
    └─ 잘못된 요청으로 거부된다
```

**빈 가지 찾기**: 트리를 목차로 읽다가 "그럼 이 경우는?"이 떠오르면, 그게 아직 방어하지 않은 경우다. 리팩터링할 때마다 빈 가지 목록을 따로 적어 두고, 채울지는 사람이 결정한다.

## 2. 테스트 본문 = given / when / then

```java
@Nested
@DisplayName("500원 결제가 이미 기록된 상태에서")
class AfterAPaymentIsRecorded {

    Account payer;
    Account payee;
    Transfer payment;

    @BeforeEach
    void givenARecordedPayment() {
        payer = ledger.openAccount("구매자");
        payee = ledger.openAccount("가맹점");
        payment = ledger.recorded(transfer(500).from(payer).to(payee));
    }

    @Test
    @DisplayName("같은 결제를 다시 보내면 이미 기록된 것으로 인정되고, 돈은 한 번만 움직인다")
    void resendingTheSamePayment() {
        // when
        CreateTransferStatus result = ledger.submit(payment);

        // then
        assertAlreadyRecorded(result);
        assertMovedExactly(ledger, payer, payee, 500);
    }
}
```

- 세 구역을 빈 줄과 `// given`, `// when`, `// then` 주석으로 나눈다. 전제가 `@BeforeEach`에 있으면 본문에는 `// given`을 쓰지 않는다.
- 전제를 설명하는 말이 필요하면 주석 뒤에 붙인다. 예: `// given: 잔액 0인 지갑에서 100원 결제가 한 번 실패했다`
- **when은 한 줄**을 목표로 한다. 행동이 둘이면(끊기 → 복구) `// when: 연결을 복구하면`처럼 구역을 하나 더 둔다.
- 테스트 하나는 행동 하나를 확인한다. 이름에 "그리고"가 둘 이상 들어가면 쪼갤 신호다.

## 3. given — 전제를 문장으로 만든다

| 쓰는 말 | 뜻 |
|---|---|
| `ledger.openAccount("구매자")` | 계좌를 연다. 이름은 실패 메시지에 그대로 나온다 |
| `ledger.openAccountThatCannotOverdraw("지갑")` | 잔액보다 많이 출금할 수 없는 계좌. 잔액은 0에서 시작한다 |
| `ledger.deposit(wallet, 100)` | 입금 |
| `transfer(500).from(payer).to(payee)` | 이체 한 건(새 ID). 이 단계에서는 아직 보내지 않는다 |
| `payment.withAmount(501)` / `payment.withNewId()` | 같은 ID에 금액만 바꾼 것 / 같은 내용에 새 ID를 붙인 것 |
| `payment.withCreditAccount(other)` 등 `with…` | 같은 ID에 그 필드만 바꾼 것 (`withDebitAccount`, `withLedger`, `withCode`, `withUserData128/64/32`) |
| `Account.notOpened("계좌")` / `ledger.open(account)` | 아직 원장에 없는 계좌 / 그 계좌를 같은 ID 그대로 연다(이미 있으면 "given 준비 실패") |
| `ledger.recorded(t)` | **전제**: 이 이체가 이미 기록되어 있다 |
| `ledger.rejected(t, ExceedsCredits)` | **전제**: 이 이체가 이 사유로 거부된 적이 있다 |

- **전제 메서드는 전제가 깨지면 그 자리에서 멈춘다.** 메시지는 `"given 준비 실패: …"`로 시작한다. then까지 가서 엉뚱한 이유로 실패하지 않게 하기 위해서다.
- 핵심 값(금액, 방향, 사유)은 숨기지 않는다. `recordedPaymentOf500()`처럼 값을 메서드 이름 속에 감추지 않는다.

## 4. when — 확인하려는 행동

| 쓰는 말 | 뜻 |
|---|---|
| `ledger.submit(t)` | 보내고, 결과 코드를 돌려받는다 |
| `ledger.submitInBackground(t)` | 보내되 응답을 기다리지 않는다(`PendingTransfer`) |

## 5. then — 일어난 효과를 메서드 하나로 확인한다

| 쓰는 말 | 확인하는 효과 (TB 코드) |
|---|---|
| `assertCreated(result)` | 새로 기록됨 (`Created`) |
| `assertAlreadyRecorded(result)` | 같은 이체가 이미 있어서 그것으로 인정됨 (`Exists`) |
| `assertConflictOnAmount(result)` | 같은 ID인데 금액이 달라서 거부됨 (`ExistsWithDifferentAmount`) |
| `assertContentConflict(result, ExistsWithDifferentX)` | 같은 ID인데 그 필드가 달라서 거부됨. 기대값이 `ExistsWithDifferent…`가 아니면 잘못 쓴 것으로 멈춘다 |
| `assertRejectedBecauseIdAlreadyFailed(result)` | 이 ID는 이미 실패했으므로 다시 판정하지 않음 (`IdAlreadyFailed`) |
| `assertRejectedFor(result, reason)` | 이 사유로 거부됨 |
| `assertMovedExactly(ledger, from, to, amount)` | from에서 정확히 amount가 나가고, to에 정확히 amount가 들어옴 |
| `assertNothingMoved(ledger, account)` | 그 계좌에는 출금·입금·보류가 하나도 없음 |
| `assertStillWaitingAfter(pending, 3s)` | 그 시간 동안 응답도 오류도 오지 않음 |

- 이름은 `assert` + **일어난 효과**로 짓는다. `assertPayerPaidPayee`처럼 역할을 이름에 넣지 않는다. 역할은 인자로 넘기고, 인자 순서는 문장 순서(어디서 → 어디로 → 얼마)를 따른다.
- 메서드 하나는 효과 하나만 확인한다. `assertCreatedAndMoved500`처럼 묶지 않는다.
- 실패 메시지는 메서드 이름과 같은 말로 쓰고, TB 원래 코드는 괄호 안에 남긴다.
  예: `같은 ID·같은 본문의 이체가 이미 기록된 것으로 인정되기(Exists)를 기대했지만 실제 결과는 Created — 처음 보내는 이체로 기록됨`
- **기대값은 손으로 쓴다.** 500, `ExceedsCredits`를 헬퍼나 프로덕션 코드로 계산하지 않는다.
- 동시성 테스트도 같은 규칙을 따른다. 필요해지는 단계에서 만든다.
  예: `assertExactlyOneApplied(outcomes)`, `assertOthersRejectedAsStaleWrite(outcomes)`, `assertHandlerRanOnceFor(command)`

## 6. 어휘를 어디에 두는가

- **여러 테스트 파일이 함께 쓰는 말**: `src/testFixtures` (`TigerBeetleTestLedger`, `LedgerAssertions`, 값 객체). 이것이 나중에 공개 테스트 키트(스펙 §8.4)가 된다.
- **한 파일에서만 쓰는 말**: 그 테스트 파일 위쪽의 private 메서드.
- **어휘 자체도 테스트한다.** assert 메서드와 전제 메서드가 틀린 입력에서 제대로 실패하는지 확인하는 테스트를 둔다(`LedgerAssertionsTest`, `TigerBeetleTestLedgerTest`). 검증 도구가 틀리면 모든 테스트가 거짓말을 하기 때문이다.

## 6-1. 표를 그대로 옮긴 테스트 (core)

스펙의 표(§4.3 전이표, §4.4 분류표)는 테스트 안에 **표 모양 그대로** 둔다.

- 표는 `static final String` text block 상수로 두고 `@CsvSource(textBlock = 상수)`로 읽는다. `#` 줄은 표 안의 소제목이다.
- `@ParameterizedTest`에는 `quoteTextArguments = false`를 붙인다. JUnit 6은 글자 인자를 따옴표로 감싸서 `"PENDING" + "TB_APPLIED"`처럼 보이게 하기 때문이다.
- 기대값은 사람이 읽는 표기로 쓴다. 예: `CLAIMED+토큰`, `0행`, `불가`. 표기를 객체로 바꾸는 `parse`는 그 테스트 파일 안에 둔다.
- 표마다 **가드 테스트**를 하나 둔다. 같은 상수를 읽어서 enum의 모든 값(또는 모든 조합)을 정확히 한 번씩 담는지 확인한다. 가드는 처음부터 통과하므로, 표의 한 줄을 지워서 실패하는지 확인한 뒤 되돌린다.
- 표의 칸을 하나씩 보는 테스트와 별도로, 표 전체에 걸친 규칙(G5: 최종 상태는 움직이지 않는다, G6: 핸들러는 최종 상태로 들어갈 때만)을 한 번 더 확인한다. 표를 고치다 실수하면 칸 테스트는 표와 함께 틀리지만, 이 규칙 테스트는 남는다.

core 테스트의 효과 이름은 그 파일 안의 private 메서드로 둔다. 예: `assertSubmits`, `assertWaits`, `assertSkips`, `assertRejectedAsInvalid`, `assertSameHash`.

## 6-2. 계약 테스트 (core testFixtures)

헥사고날 구조라서 계약이 두 벌이다. 저장소 구현마다 `StorageUnderTest` 하나를 만들고, 두 계약을 각각 상속한 테스트를 둔다. 메모리 저장소와 PostgreSQL 저장소가 같은 장을 읽는다.

- `CommandStoreContract`: 저장소 포트의 의미. 넣기, 찾기, 순번, 잠그고 읽기, 조건부 쓰기, 트랜잭션. 행은 손으로 만든다. 상태 기계를 거치지 않으며, 저장소가 판단하지 않는다는 것을 보여 준다.
- `LedgerRelayContract`: 서비스 `CommandLifecycle`의 행동. 접수, 참조 검사, G1, claim과 게이트, 결과, 늦은 워커, 핸들러 실패, 순서.

정상 경로에서 나올 수 없는 행(순서가 뒤집힌 확정, 예약 행이 없는 확정)은 `acceptedPastTheChecks(postRow(…))`로 접수 검사를 거치지 않고 저장소에 바로 넣어 만든다. 그래서 멈춤(HALTED)과 포기 조치도 저장소마다 같은 장으로 확인한다.

### 동시성 장 — 다른 트랜잭션을 결정적으로 만든다

`CommandStoreContract` 7장은 두 트랜잭션이 겹치는 순간을 다룬다. 스레드를 아무렇게나 띄우고 운에 맡기지 않는다. 다른 트랜잭션은 `OtherTransaction`으로 열고, 그 트랜잭션이 언제 일을 하고 언제 끝나는지를 테스트가 정한다.

| 말 | 뜻 |
|---|---|
| `OtherTransaction.start(transactions, () -> …)` | 다른 스레드에서 트랜잭션을 열어 일을 한다. 일을 마쳐도 커밋하지 않고 기다린다. 그래서 잡은 행 잠금이 살아 있다 |
| `awaitWork()` | 일이 끝나기를 기다려 결과를 받는다(최대 10초). 일이 던졌으면 그 예외가 나온다 |
| `assertStillWorkingAfter(BLOCKED)` | 300ms 동안 일이 끝나지 않았다 = 무언가에 막혀 있다. "아무 일도 없어야 한다"를 볼 때만 고정 관찰 시간을 쓴다(§8) |
| `commit()` / `rollback()` | 그 트랜잭션을 끝내고, 끝날 때까지 기다린다 |
| `try (…)` | 닫을 때 아직 열려 있으면 롤백한다. 실패한 테스트가 잠금을 남기지 않게 |

행 잠금이 없는 저장소(메모리)는 `StorageUnderTest.supportsRowLocks()`가 false라 이 장을 건너뛴다. 메모리 저장소는 모든 트랜잭션을 잠금 하나로 줄 세우므로 이 장이 실패할 수가 없기 때문이다. 같은 이유로 시계가 흐르는 저장소(PostgreSQL의 `now()`)는 `clockStandsStill()`이 false라 "바로 그 시각" 경계 테스트를 건너뛴다. 어휘 자체는 `OtherTransactionTest`가 확인한다(막혀 있지 않은데 막혀 있다고 보면 실패하는지 포함).

| 쓰는 말 | 뜻 |
|---|---|
| `newTransfer("pay-1", "order-1", 1)`, `newReserve(…)`, `newPost("capture-1", "order-1", 2, "hold-1")` | (저장소 계약) 넣을 행. 이체 ID는 100+순번, 해시는 `hash-of-<id>` |
| `inserted(row)` / `claimed(id, token)` / `finished(id, row)` / `released(id)` / `halted(id)` | (저장소 계약) **전제**: 행이 들어감 / 처리 중이 됨 / 끝남 / 되돌아감 / 멈춤 |
| `assertDue(ids…)` / `assertDueWithLimit(n, ids…)` / `assertNothingDue()` | (저장소 계약) claim 후보가 정확히 이것(접수 순서) / 없음 |
| `transfer("pay-1", "order-1", 500)`, `reserve(…)`, `post("capture-1", "order-1", "hold-1")`, `voidOf(…)` | (서비스 계약) 명령. 키와 금액을 숨기지 않는다 |
| `submit(command)` | (서비스 계약) 앱처럼 트랜잭션 안에서 접수한다 |
| `acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102))`, `reserveRow(…)` | (서비스 계약) **전제**: 접수 검사를 거치지 않은 행이 들어감 |
| `assertHalted(batch, ids…)` / `assertNothingHalted(batch)` / `assertRow(id, haltedRow(1))` | 멈춘 명령으로 알림 / 알림 없음 / 저장된 행이 정확히 이것 |
| `claimOnly("pay-1")` | **전제**: 이 명령 하나만 잡힌다. 아니면 "given 준비 실패" |
| `finished("hold-1", ExceedsCredits)` | (서비스 계약) **전제**: 이 명령이 이 TB 결과로 끝났다 |
| `storage.elapse(LEASE.plusSeconds(1))` | 저장소 시계를 돌린다(lease 만료, backoff 경과). sleep하지 않는다 |
| `handler.failNextCalls(1)` | 다음 핸들러 호출이 기록 전에 던진다 |
| `handler.crashNextCall()` | 다음 핸들러 호출이 기록 전에 `Error`를 던진다(JVM 수준 실패 흉내) |
| `assertQueryState(id, State.X)` | 조회 상태(§4.2) |
| `assertClaimed(batch, ids…)` / `assertNothingClaimed(batch)` | 정확히 이 명령들이 접수 순서로 잡힘 / 아무것도 안 잡힘 |
| `assertWritten(r)` / `assertFenced(r)` / `assertHandlerFailed(r)` | 쓰기가 반영됨 / 0행으로 막힘 / 핸들러가 던져 취소됨 |
| `assertHandlerRanOnceFor(id, State.X)` / `assertHandlerNeverRanFor(id)` | 핸들러가 끝까지 한 번 실행됨 / 한 번도 끝까지 실행되지 않음 |
| `assertRejection(id, category, code)` | 거절·충돌 사유 |
| `assertRejectedAsConflict(…)` / `assertRejectedAsMissingReservation(…)` | 접수가 내용 충돌 / 참조 예약 없음으로 거부됨 |

## 7. 리팩터링 규칙

- 같은 준비나 확인이 **두세 번 반복되고 같은 의미**일 때 이름을 붙여 뽑는다. 생김새만 비슷하다고 묶지 않는다.
- 뽑은 이름은 도메인의 말이어야 한다(`recorded`, `assertMovedExactly`). 기술의 말(`createAndCheckBatch`)은 쓰지 않는다.
- 테스트 리팩터링은 동작을 바꾸지 않는다. 바꾸기 전후 전체 스위트가 같은 결과인지 확인한다. 새 경우를 추가하는 작업은 별도로 한다.

## 8. TDD 규율 (스펙 §8.1)

- 실패 테스트를 먼저 쓰고, **의도한 이유로** 실패하는지 확인한다. 컴파일 오류는 Red로 인정하지 않는다.
- 외부 시스템(TB)의 동작을 고정하는 특성화 테스트는 처음부터 통과할 수 있다. 그 대신 기대값 하나를 일부러 틀리게 바꿔 실패하는지 확인한다.
- 동시성은 sleep이 아니라 체크포인트와 latch로 실행 순서를 강제한다. "아무 일도 없어야 한다"를 관찰할 때만 고정된 관찰 시간을 쓴다.
