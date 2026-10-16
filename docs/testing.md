# 테스트 작성 규칙

ledger-relay의 테스트는 **읽으면 스펙이 되는 문서**다. 테스트 트리를 위에서 아래로 읽으면 이 코드가 무엇을 지키는지 알 수 있어야 한다. 기준 사례는 `~/projects/hexai`이다.

## 1. 테스트 트리 = 스펙 목차

- 테스트 클래스는 대상 하나를 맡는다. `@DisplayName`에는 무엇의 규칙인지와 스펙 근거를 적는다.
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

## 7. 리팩터링 규칙

- 같은 준비나 확인이 **두세 번 반복되고 같은 의미**일 때 이름을 붙여 뽑는다. 생김새만 비슷하다고 묶지 않는다.
- 뽑은 이름은 도메인의 말이어야 한다(`recorded`, `assertMovedExactly`). 기술의 말(`createAndCheckBatch`)은 쓰지 않는다.
- 테스트 리팩터링은 동작을 바꾸지 않는다. 바꾸기 전후 전체 스위트가 같은 결과인지 확인한다. 새 경우를 추가하는 작업은 별도로 한다.

## 8. TDD 규율 (스펙 §8.1)

- 실패 테스트를 먼저 쓰고, **의도한 이유로** 실패하는지 확인한다. 컴파일 오류는 Red로 인정하지 않는다.
- 외부 시스템(TB)의 동작을 고정하는 특성화 테스트는 처음부터 통과할 수 있다. 그 대신 기대값 하나를 일부러 틀리게 바꿔 실패하는지 확인한다.
- 동시성은 sleep이 아니라 체크포인트와 latch로 실행 순서를 강제한다. "아무 일도 없어야 한다"를 관찰할 때만 고정된 관찰 시간을 쓴다.
