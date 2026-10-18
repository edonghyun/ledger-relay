# 장애 대응 기록

ledger-relay가 막아야 하는 장애를 하나씩 적는다. 장애마다 **무엇이 깨지는지 → 어떻게 재현했는지 → 구조로 어떻게 막았는지 → 무엇으로 확인하는지**를 한 줄로 잇는다.

- 재현은 테스트로 한다. 테스트 이름을 적어 두면 그 장애가 다시 생겼을 때 어느 테스트가 실패할지 알 수 있다.
- 처음부터 통과하는 테스트는 코드를 일부러 망가뜨려(대조군) 정말 실패하는지 확인한다. 대조군 결과도 적는다.
- 수치는 실제로 잰 것만 적는다. 운영 지표와 실험(스펙 §9 E1~E10)은 해당 단계에서 채운다.

## 1. 막은 장애 (F1~F10은 02a core, F11~F19는 02b jdbc, F20은 1단계 리뷰, F21~F25는 03a 워커, F26은 테스트 환경, F27부터 02d 전체 리뷰, F32·F33은 02e, F34~F36은 03b, F37부터 샘플 쇼핑몰)

### F1. 막힌 키의 대기 명령이 claim 한도를 다 차지해 다른 키가 굶는다

- **깨지는 것**: 한 키의 앞 명령이 처리 중이면 뒤 명령들은 기다린다. 저장소가 이 대기 명령들까지 후보로 내면, 한 번에 잡는 한도(limit)가 대기 명령으로 차서 다른 키가 영영 잡히지 않는다.
- **재현**: `CommandStoreContract` › claim 후보를 잠그면 › `aBlockedKeyDoesNotUseUpTheLimit`. 키 a에 명령 셋이 있고 맨 앞이 처리 중일 때, limit 1로 잠그면 키 b의 명령이 나와야 한다.
- **구조**: 저장소는 순서 키마다 맨 앞의 미완료 명령만 후보로 낸다(P2a-19). 키마다 맨 앞 판정(G4)은 저장소 계약(`CommandStoreContract`)이 메모리와 PG 양쪽에서 고정하며, core는 이것을 다시 확인하지 않는다. core의 게이트는 참조한 예약만 본다(02d P2d-10).
- **확인**: 맨 앞 거르기를 지운 대조군에서 3건이 실패한다. jdbc에서 행 잠금과 함께 다시 확인했다(F12).

### F2. lease가 끝나 다른 워커가 가져간 명령에 앞 워커가 결과를 쓴다

- **깨지는 것**: 느린 워커가 늦게 쓴 결과가 반영되면, 결과가 두 번 쓰이거나 핸들러가 두 번 실행된다(G5).
- **재현**: `LedgerRelayContract` › lease가 끝나 다른 워커가 잡은 뒤 앞 워커가 쓰면 › `theLateResultIsFenced`, `theCurrentWorkerWins`.
- **구조**: claim할 때마다 lease 토큰이 오른다. 모든 쓰기는 "상태가 처리 중이고 토큰이 같을 때만"이다. 상태 기계에서 한 번, 저장소의 조건부 쓰기에서 한 번 막는다(P2a-7).
- **확인**: 서비스가 토큰을 보지 않게 바꾼 대조군에서 2건이 실패한다.

### F3. 결과는 기록됐는데 결과 반영 핸들러의 효과는 없다

- **깨지는 것**: 핸들러가 던진 뒤에도 최종 상태가 커밋되면, 앱의 업무 상태(주문, 지갑)가 원장 결과를 영영 따라가지 못한다(G6).
- **재현**: `LedgerRelayContract` › 결과 반영 핸들러가 실패하면 › `theResultIsRolledBackWithTheHandler`. 저장소 쪽은 `CommandStoreContract` › `aThrowingTransactionUndoesTheWrite`.
- **구조**: 최종 쓰기와 핸들러를 한 트랜잭션에서 실행한다. 핸들러가 던지면 롤백하고, 새 트랜잭션에서 실패를 기록한다(P2a-22).
- **확인**: 핸들러 예외에도 커밋하는 대조군에서 4건이 실패한다. PostgreSQL에서 DB에 쓰는 핸들러로 다시 확인했다(`HandlerAtomicityTest`). 핸들러가 트랜잭션 밖 별도 커넥션(auto-commit)으로 쓰는 대조군에서 2건이 실패한다. 쓴 뒤 던지면 앱 테이블에 기록이 남고(`neitherTheResultNorTheAppRowRemains`), 재시도는 기본 키 위반으로 다시 실패해 적용으로 끝나지 못한다(`retryingLeavesExactlyOneAppRow`). 처음 쓴 재시도 테스트는 기록 수만 봐서 이 대조군을 놓쳤고, 최종 상태 확인을 더해 고쳤다.

### F4. 핸들러 실패 뒤 다시 보내면 원래 거절 사유가 사라진다

- **깨지는 것**: 잔액 부족으로 거절된 명령의 핸들러가 실패한 뒤 TB에 다시 보내면, TB는 `IdAlreadyFailed`만 돌려준다. 원래 사유(`ExceedsCredits`)를 잃는다.
- **재현**: `LedgerRelayContract` › `theKnownResultComesBackWithTheNextClaim`. 상태 기계는 `CommandStateMachineTest` › 이미 아는 TB 결과가 있으면 › `submittingAgainIsABug`.
- **구조**: 받은 TB 결과를 `known_result`로 남기고, 다음 claim은 TB에 보내지 않고 그 결과로 끝낸다. 상태 기계가 재제출을 버그로 거부한다.
- **확인**: 실패 기록을 지운 대조군에서 3건이 실패한다.

### F5. 핸들러가 `Error`를 던지면 결과가 남지 않는다

- **깨지는 것**: `Error`(메모리 부족 등)는 핸들러 실패 처리를 타지 않아, F4와 같은 이유로 원래 사유를 잃는다.
- **재현**: `LedgerRelayContract` › 결과 반영 핸들러가 Error를 던지면 › `theKnownResultIsKept`.
- **구조**: `Error`도 같은 경로로 롤백하고 결과를 새 트랜잭션에 남긴 뒤, `Error`는 삼키지 않고 그대로 던진다.
- **확인**: 고치기 전에는 실패했다(Red). `Error`를 삼키는 대조군에서 `theErrorIsRethrown`이 실패한다.

### F6. 워커 단계를 바깥 트랜잭션 안에서 부르면 F3이 다시 생긴다

- **깨지는 것**: 워커 단계가 호출자의 트랜잭션에 합류하면, 핸들러 실패의 롤백이 바깥에 묻혀 최종 상태만 커밋된다. 최종 리뷰에서 실제로 재현됐다.
- **재현**: `LedgerRelayContract` › 워커 단계를 앱 트랜잭션 안에서 부르면 › `completingInsideATransactionIsRefused`.
- **구조**: `Transactions.isActive()`를 두고, 서비스는 활성 트랜잭션 안에서 부른 claim과 쓰기를 거부한다.
- **확인**: 고치기 전에는 실패했다(Red).

### F7. 저장소가 핸들러 실패 횟수를 버려 backoff가 늘지 않는다

- **깨지는 것**: 계속 실패하는 핸들러가 늘 첫 대기만큼만 쉬고 다시 잡힌다. 막힌 키를 드러내는 지표(`handler_failures`)도 0으로 보인다.
- **재현**: `CommandStoreContract` › `everyColumnOfTheRowIsKept`, `LedgerRelayContract` › `aSecondFailureWaitsTwiceAsLong`.
- **구조**: 두 계약이 행 전체의 왕복과 대기 증가를 고정한다. jdbc 저장소도 같은 계약을 통과해야 한다.
- **확인**: 핸들러 실패 횟수만 버리는 대조군에서 정확히 이 2건이 실패한다.

### F8. TB를 올리면서 결과 코드 이름이 바뀌어 저장된 결과를 읽지 못한다

- **깨지는 것**: Java 상수 이름(`name()`)으로 저장하면, TB 클라이언트가 이름을 바꿀 때 `known_result`를 읽지 못해 그 키가 영원히 막힌다.
- **재현**: `ResultCodeWireNameTest`.
- **구조**: 68개 코드마다 TB 소스의 snake_case 저장 이름을 손으로 고정한다. Java 상수 이름은 저장 이름으로 받지 않는다.
- **확인**: 표에서 한 줄을 지운 대조군에서 가드가 실패한다.

### F9. 같은 키 안에서 확정이 예약보다 앞 순번이 된다 (HALT)

- **깨지는 것**: 확정을 예약보다 먼저 TB에 보내면 `PendingTransferNotFound`로 잘못 거절되고, 뒤에 만들어진 예약 금액은 timeout까지 묶인다.
- **원인**: 접수 검사가 이 순서를 막으므로 버그나 DB 직접 수정이 아니면 생기지 않는다.
- **1차 대응의 한계**: 처음에는 멈추기만 했다. 행은 접수 상태로 남아 claim할 때마다 다시 알리고 limit 한 칸을 차지했으며, 조회하면 '접수됨'으로 보여 원인을 찾기 어려웠다.
- **재현**: `LedgerRelayContract` › 같은 키에서 확정이 자기가 참조한 예약보다 앞 순번인 상태에서. 순서를 뒤집은 행을 접수 검사 없이 저장소에 바로 넣는다. 계약이므로 jdbc 저장소도 같은 장을 통과해야 한다.
- **구조**: 게이트가 멈추면 그 명령을 토큰을 올려 HALTED로 주차한다. HALTED는 최종 상태가 아니라 그 키의 맨 앞으로 남아 뒤 명령을 막고(G4), claim 후보가 아니므로 limit을 차지하지 않으며 알림은 한 번이다. 다른 키는 그대로 처리된다. 데드레터큐처럼 빼내지 않는 이유는 3절에 있다.
- **사후 처리**: 운영자가 원인을 고친 뒤 `abandonHalted(commandId)`로 포기한다. 확정은 `SKIPPED`(이유 `ABANDONED_BY_OPERATOR`)로 끝나고, 같은 트랜잭션에서 핸들러가 그 이유를 받는다. 키가 풀려 뒤의 예약이 처리되고, 앱은 새 commandId로 다시 확정한다. TB에는 한 번도 가지 않았으므로 원장에 잘못된 이동이 없다. 핸들러가 실패하거나 `Error`를 던지면 멈춘 그대로 남아 다시 포기할 수 있다. `abandonHalted`는 지금 내부 서비스(`CommandLifecycle`)에만 있고, 앱이 부를 공개 포트(멈춘 명령 목록·포기)는 03b에서 낸다. 이미 TB에 보낸 적이 있는 명령은 포기를 거부한다(F27).
- **확인**: HALT를 OPEN으로 처리한 대조군에서 10건, HALTED를 후보로 내는 저장소 대조군에서 4건이 실패한다. 저장소 계약은 멈춘 행의 완료 시각이 비어 있음을 고정한다(완료 시각을 찍는 대조군에서 실패). 멈춘 키 수 지표와 health는 3단계에서 단다.

### F10. 확정이 참조한 예약 행이 없다 (HALT 유지, 판단 번복)

- **깨지는 것**: 예약이 충돌(CONFLICT)로 끝났다면 TB에는 같은 이체 ID로 **다른 내용의 이체**가 있다. 그 상태에서 확정을 보내면 남의 pending 이체를 금액 전체로 확정할 수 있다. 저장소가 잠긴 예약 행을 읽지 못해 "없음"으로 보인 경우라면 확정이 예약보다 먼저 가서 F9와 같은 피해가 난다.
- **판단의 변화**: 한때 "예약의 TB 이체 ID를 접수 때 고정하므로(P2a-20) 엉뚱한 예약을 확정할 길이 없다. 보관 정책이 끝난 예약을 지운 것이니 TB에 맡기자"고 판단해 게이트를 OPEN으로 바꿨다. 리뷰에서 두 가지가 틀렸음이 드러났다. 첫째, ID가 고정돼도 그 ID에 TB가 가진 이체가 우리 것이라는 보장이 없다(CONFLICT). 둘째, 스펙의 보관 정책은 미완료 확정이 참조하는 예약을 지우지 않으므로 "행이 없음"은 정상 경로가 아니다. 그래서 되돌렸다.
- **재현**: `LedgerRelayContract` › 확정이 참조한 예약 행이 없는 상태에서. 게이트는 `OrderingGateTest` › `haltsWhenTheReservationIsMissing`.
- **구조**: F9와 같이 멈추고 HALTED로 주차한다. 멈춤이 이제 싸므로(한 번 알림, limit을 차지하지 않음, 포기 조치 있음) 보내는 쪽으로 바꿀 이유가 없다.
- **확인**: OPEN으로 되돌린 대조군에서 3건이 실패한다. `lockDue`가 참조 예약을 잠금과 상관없이 읽는지는 02b 동시성 장에서 확인했다(F13).

### F11. 같은 트랜잭션에서 접수한 예약을 확정이 참조한다

- **깨지는 것**: 스펙 §5.1-3은 확정·해제가 참조하는 예약이 "이미 커밋되어" 있기를 요구한다. 같은 앱 트랜잭션 안에서 예약과 확정을 함께 접수하면 두 명령의 운명이 한 트랜잭션에 묶인다. 그 트랜잭션의 다른 쿼리가 실패해 롤백되면 둘 다 사라지는 것은 괜찮지만, 앱이 저장점(savepoint)으로 예약만 되돌리면 참조할 곳이 없는 확정이 남는다. 그러면 F10처럼 멈춘다. 메모리 저장소는 이 경우를 받아들이고 있었다.
- **재현**: `LedgerRelayContract` › 같은 앱 트랜잭션에서 예약을 접수하고 이어서 그 예약을 참조하면 › `thePostIsRefused`. 저장소 표시는 `CommandStoreContract` › `aRowIsMarkedAsAcceptedInThisTransactionUntilCommit`.
- **구조**: 저장소는 사실만 알려 준다. `CommandRecord.acceptedInThisTransaction()`은 그 행을 지금 트랜잭션이 넣었는지를 나타낸다. PostgreSQL에서는 `accepted_xact xid8`과 `pg_current_xact_id_if_assigned()`를 비교한다. 거부는 서비스(`CommandLifecycle`)가 `ReservationNotFoundException`으로 한다(P2b-1·P2b-2).
- **확인**: 두 테스트 모두 구현 전에 실패했다(표시가 false였고, 거부 예외가 없었다).

### F12. 잠긴 맨 앞 명령을 건너뛰고 그 키의 다음 명령을 낸다

- **깨지는 것**: 워커 A가 키 order-1의 1번을 잡고 TB에 보내는 중이다. 워커 B가 `lockDue`를 하면서 "맨 앞인가"를 판정할 때 잠긴 1번까지 건너뛰면, 2번이 맨 앞으로 보여 함께 나간다. 키 안의 순서(G4)가 깨진다. 확정이 예약보다 먼저 가는 F9가 동시성으로 다시 생긴다.
- **재현**: `CommandStoreContract` › 다른 트랜잭션이 행을 잡고 있으면 › `aLockedHeadHidesItsWholeKey`. 다른 트랜잭션(`OtherTransaction`)이 1번을 잠근 채로 두고 `lockDue`를 부른다.
- **구조**: 후보 행만 `FOR UPDATE OF c SKIP LOCKED`로 잠근다. 맨 앞 판정(`NOT EXISTS` 앞 순번 미완료)은 잠그지 않고 읽으므로, 잠긴 1번도 "미완료"로 보인다(P2b-10).
- **확인**: 대조군으로 `NOT EXISTS` 하위 질의에 `FOR UPDATE SKIP LOCKED`를 붙이면 이 테스트만 실패한다.

### F13. 잠긴 예약 행 때문에 확정이 예약을 "없음"으로 본다

- **깨지는 것**: 다른 트랜잭션이 예약 행을 쓰는 중이라 잠겨 있을 때, 확정 후보가 그 예약을 잠가서 읽으려 하면 예약이 건너뛰어져 "참조 예약 없음"이 된다. 게이트는 F10에 따라 멈춘다(HALTED). 돈은 안전하지만, 멀쩡한 확정이 운영자 조치가 필요한 상태로 빠진다.
- **재현**: `CommandStoreContract` › `aLockedReservationIsStillReported`.
- **구조**: 예약은 `LEFT JOIN`으로 잠그지 않고 읽는다. 잠금은 후보 행(`OF c`)에만 건다(P2b-10).
- **확인**: 대조군으로 예약을 `LEFT JOIN LATERAL (… FOR UPDATE SKIP LOCKED)`로 읽게 바꾸면 이 테스트만 실패한다("잠긴 예약을 읽은 결과"가 비어 있다). 단순히 `FOR UPDATE SKIP LOCKED`로 넓히는 실수는 PostgreSQL이 문법 단계에서 거부한다(외부 조인의 nullable 쪽은 잠글 수 없다). 그래서 의미를 보여 주는 대조군으로 쓸 수 없었다.

### F14. 접수 커밋 순서와 순번 순서가 어긋난다 (R12)

- **깨지는 것**: 같은 키에 두 접수가 겹칠 때 순번을 `max(seq)+1`로 내면, 둘이 같은 번호를 보거나 늦게 커밋한 쪽이 작은 번호를 가진다. 워커가 작은 번호를 먼저 처리했다고 믿는 동안 그 번호의 행이 나중에 나타난다. 맨 앞 판정(F12)은 "나보다 앞선 명령이 나중에 생기지 않는다"는 전제에 기대므로 그 전제가 무너진다.
- **재현**: `CommandStoreContract` › `theKeyLockHoldsUntilCommit`. 첫 접수가 순번 1을 받고 커밋하지 않은 동안 두 번째 접수는 막혀 있어야 하고, 첫 접수가 커밋한 뒤 2를 받아야 한다.
- **구조**: 키 행(`ledger_ordering_key`)을 upsert해 잠그고 번호를 낸다. 잠금은 커밋까지 유지되므로 같은 키의 접수는 줄을 선다(P2b-8). 잠글 행이 없는 집합 판단을 잠글 행 하나로 바꾼 것이다(4절 "격리 수준과 무관한 이유").
- **확인**: 대조군으로 `max(seq)+1`로 바꾸면 이 테스트와 `eachKeyCountsFromOne`이 실패한다(두 번째가 막히지 않는다).

### F15. 같은 commandId의 동시 접수가 앱 트랜잭션을 깬다 (G7)

- **깨지는 것**: 유일성 위반 예외를 잡아 "이미 있다"로 처리하면, PostgreSQL은 그 트랜잭션 전체를 중단 상태로 만든다. 그 뒤의 모든 질의가 `current transaction is aborted`로 실패하고 앱 트랜잭션이 통째로 롤백된다. 재시도한 사용자의 주문 저장이 함께 날아간다.
- **재현**: `CommandStoreContract` › `aConcurrentDuplicateLeavesTheAppTransactionUsable`. 늦은 쪽은 먼저 커밋된 행을 돌려받고, 이어서 다른 키의 순번을 받을 수 있어야 한다.
- **구조**: `INSERT … ON CONFLICT (command_id) DO NOTHING RETURNING id`. 넣지 못했으면 같은 트랜잭션에서 기존 행을 읽는다(P2b-9). 에러를 내지 않으므로 트랜잭션이 산다.
- **확인**: 대조군으로 `ON CONFLICT`를 지우고 `DuplicateKeyException`을 잡게 하면 이 테스트와 `aSecondInsertReturnsTheFirstRow`가 `current transaction is aborted`로 실패한다.

### F16. JPA 트랜잭션에서 접수가 따로 커밋된다 (G1, R16)

- **깨지는 것**: 앱이 `JpaTransactionManager`를 쓰는데 그 트랜잭션이 JDBC 커넥션을 노출하지 않으면, `JdbcClient`는 DataSource에서 별도 커넥션을 받아 auto-commit으로 쓴다. 트랜잭션은 활성으로 보이므로(`requireActive` 통과) 아무 경고도 없다. 앱이 롤백해도 명령은 남는다. 주문은 없는데 원장 이체가 나간다.
- **재현**: `JpaLedgerRelayTest` › 앱이 JPA로 주문을 저장하는 트랜잭션에서 접수하면 › `anAppEntityAndItsCommandRollBackTogether`. 상속한 서비스 계약 전체도 JPA 매니저 위에서 돈다.
- **구조**: `JpaTransactionManager`에 같은 DataSource를 주고(`setDataSource`), `HibernateJpaDialect`가 JDBC 커넥션을 노출한다(P2b-13).
- **확인**: 격리 수준은 받아들이지만 커넥션을 노출하지 않는 dialect로 바꾼 대조군에서 12건이 실패한다(이 대조군은 F18 이전 기록이다. 지금은 그 설정 자체가 접수에서 거부된다). 이 테스트("롤백된 명령"이 남아 있다)와 계약의 롤백 관련 장 11건(G1, G6, P2b-1)이다. 처음에 계획한 대조군 `DefaultJpaDialect`는 격리 수준 지정을 거부해서(P2b-16) 69건 전부가 트랜잭션 시작에서 실패했다. 이 실패는 합류 문제를 보여 주지 않으므로 대조군으로 쓰지 않았다.

### F17. 워커가 커넥션 풀의 기본 격리 수준을 따라가 직렬화 실패로 터진다

- **깨지는 것**: 앱이 커넥션 풀 기본값을 REPEATABLE READ로 두면, 워커 단계(claim, 결과 쓰기)도 그 수준으로 열린다. 그러면 다른 워커와 같은 행을 건드릴 때 기다리지 않고 직렬화 실패(40001)로 끝난다. 워커는 이 실패를 예상하지 않으므로, 결과를 쓰지 못한 명령이 lease 만료까지 묶인다.
- **재현**: `JdbcTransactionsTest` › 커넥션 풀의 기본 격리 수준이 REPEATABLE READ이면 › `aNewTransactionRunsAtReadCommitted`.
- **구조**: `JdbcTransactions`가 새로 여는 트랜잭션은 READ COMMITTED로 명시한다. 앱 트랜잭션에 합류할 때는 앱의 수준을 바꾸지 않는다(`joiningKeepsTheAppsLevel`, P2b-16).
- **확인**: 격리 수준을 명시하기 전에 이 테스트가 실패했다("repeatable read"를 읽었다).

### F18. 저장소의 커넥션이 묶이지 않은 트랜잭션에서 접수가 따로 커밋된다 (G1, 리뷰에서 발견)

- **깨지는 것**: F16의 대조군 상황이 실제 설정에서도 생긴다. JDBC 커넥션을 노출하지 않는 JPA dialect를 쓰거나, 트랜잭션 매니저와 저장소가 서로 다른 DataSource 객체를 쥔 경우다(p6spy·datasource-proxy 같은 프록시). 그러면 트랜잭션은 열려 있어도 `JdbcClient`가 별도 커넥션으로 auto-commit한다. 주문은 롤백되는데 이체가 나가고, 키 잠금도 커밋까지 가지 않아 R12도 깨진다. 처음에는 열린 장애 O6으로 미뤘지만, 조용히 돈이 나가는 경로라 02b에서 막았다.
- **재현**: `JdbcTransactionsTest` › 열린 트랜잭션에 저장소의 커넥션이 묶여 있지 않으면 › `aJpaTransactionHidingTheConnectionIsRefused`, `aTransactionOnAnotherDataSourceIsRefused`.
- **구조**: `JdbcTransactions(transactionManager, dataSource)`가 저장소와 같은 DataSource를 받는다. `requireActive`는 트랜잭션이 열려 있는지와 함께 그 DataSource의 커넥션이 트랜잭션에 묶여 있는지(`TransactionSynchronizationManager.getResource`)를 확인하고, 아니면 `IllegalTransactionStateException`으로 거부한다.
- **확인**: 확인을 넣기 전에 두 테스트 모두 "예외가 없다"로 실패했다.

### F19. claim 폴링 비용이 끝난 이력 크기에 비례한다 (리뷰에서 발견)

- **깨지는 것**: `lockDue`는 접수 순서(`ORDER BY c.id LIMIT n`)로 후보를 고른다. 이 조건을 받치는 인덱스가 없으면 플래너가 기본 키 순서로 훑으면서 끝난 행을 하나씩 걸러 낸다. 워커가 짧은 간격으로 폴링하므로 DB 부하가 이력 크기에 비례해 커진다. 보관 정책은 기본값이 꺼져 있다.
- **재현**: `LockDueScanTest` › 끝난 명령 20만 건 뒤에 접수된 명령 100건이 있으면 › `theDueQueryDoesNotFilterThroughFinishedRows`. 같은 질의의 `EXPLAIN ANALYZE`에서 "Rows Removed by Filter"를 더한 값이 100 이하여야 한다. 접수된 명령이 1건뿐이면 기존 부분 인덱스(`ledger_command_unfinished`)가 쓰여 재현되지 않았다. 100건으로 늘리자 기본 키 스캔이 골라졌다.
- **구조**: 부분 인덱스 `ledger_command_due ON (id) WHERE state IN ('PENDING','CLAIMED')`(02e에서 `ledger_command_ready`로 바뀜. 맨 앞만 담으므로 끝난 이력도 읽지 않는다, F32). `theDueQueryDoesNotFilterThroughFinishedRows`는 새 질의에서도 그대로 지난다.
- **확인**: 인덱스 전에는 걸러 낸 행이 200,000, 실행 21.8ms였다. 인덱스 뒤에는 테스트가 통과한다(리뷰어 측정: 100만 건에서 151ms → 0.047ms).

### F20. U+0000이 든 문자열이 접수를 SQL 오류로 깨고, 짝 없는 서로게이트가 해시를 충돌시킨다 (1단계 리뷰 M-2)

- **깨지는 것**: PG `text`는 0x00 바이트를 받지 않는다. commandId·순서 키·reservation에 U+0000이 있으면 접수의 INSERT가 SQL 오류로 끝나고, PG에서는 그 앱 트랜잭션 전체가 중단된다. 짝 없는 서로게이트(예: `\uD800`)는 `getBytes(UTF_8)`에서 `?`로 바뀐다. 그래서 `"order-\uD800"`과 `"order-?"`의 내용 해시가 같아져, 다른 명령이 같은 내용으로 인정될 수 있다(G7).
- **재현**: `LedgerCommandTest` › 규칙을 어긴 명령을 만들면 › "commandId에 U+0000이 있으면", "순서 키에 짝 없는 서로게이트가 있으면", "확정의 reservation에 U+0000이 있으면". 짝이 맞는 서로게이트(이모지)는 `aTransferWithAPairedSurrogateIsAccepted`가 허용함을 확인한다.
- **조회·포기 경로 (리뷰에서 발견)**: 접수만 막으면 `status(commandId)`와 `abandonHalted(commandId)`가 같은 문자열을 그대로 PG에 보낸다. `status`는 앱 트랜잭션에 합류하므로, 외부 키(HTTP 경로의 멱등 키 등)로 조회한 앱 트랜잭션이 U+0000 하나로 통째로 깨진다. 짝 없는 서로게이트는 pgjdbc가 `?`로 바꿔 보내므로, `status("pay-\uD800")`가 `pay-?`라는 다른 명령의 상태를 돌려주고, `abandonHalted`는 다른 명령을 포기하려 든다. commandId는 해시에 들어가지 않으므로 이쪽 위험은 해시 충돌이 아니라 다른 명령과 맞는 것이다.
- **재현**: `LedgerCommandTest` › 규칙을 어긴 명령을 만들면 › 위 세 행. `LedgerRelayContract` › 명령을 접수하면 › `aMalformedIdIsNotFoundAndTheAppTransactionStaysUsable`, `aLoneSurrogateDoesNotMatchAnotherCommand`, 멈추지 않은 명령을 포기하려 하면 › `aLoneSurrogateIdIsRefusedAsUnknown`. 메모리 저장소에서는 실패할 수 없고 PG 위(DataSource·JPA 두 계약)에서만 실패한다.
- **구조**: `LedgerCommand` 생성자의 `requireWellFormed`가 세 문자열 필드를 검사하고 `InvalidCommandException`으로 거부한다. 같은 판정을 공개 정적 메서드 `LedgerCommand.isAcceptableId`로 내고, 서비스의 `status`는 접수될 수 없는 id면 DB에 묻지 않고 "없음"을, `abandonHalted`는 "접수한 적 없는 명령"을 돌려준다. 판단이 core에 있으므로 저장소는 이런 값을 받지 않는다.
- **확인**: 검사를 넣기 전에 생성자 세 행이 "예외가 없다"로, 계약 세 테스트가 PG 위에서 6건(SQL 오류, `pay-?`의 상태, 다른 명령의 HALTED 검사) 실패했다.

### F21. submit-timeout 뒤 슬롯을 바로 돌려주면 TB가 멈춘 동안 요청이 끝없이 쌓인다 (03a)

- **깨지는 것**: TB Java 클라이언트에는 timeout이 없다. 워커가 submit-timeout으로 기다림을 끊어도 요청은 클라이언트 안에 남아 TB가 돌아올 때까지 다시 보내진다. 그때 in-flight 슬롯을 돌려주면 워커는 다음 바퀴에 또 claim하고 또 보낸다. TB가 멈춘 동안 클라이언트 안에 요청이 끝없이 쌓이고, TB가 돌아오는 순간 한꺼번에 몰린다.
- **재현**: `RelayWorkerTest` › TB가 submit-timeout 안에 답하지 않으면 › `aTimedOutRequestKeepsItsSlotsUntilItEnds`. 슬롯 4에 이체 둘을 보내 timeout이 나면 남은 슬롯이 2여야 하고, 붙잡힌 응답이 끝난 뒤에야 4가 된다. 슬롯을 claim 전에 잡는 것은 `noSlotsMeansNoClaim`이 고정한다. 슬롯이 없으면 claim하지 않으므로 명령은 lease 없이 접수됨으로 남는다.
- **구조**: `RelayWorker`는 claim 전에 batch-size만큼 슬롯을 잡는다(P3a-2). 보내지 않는 몫은 바로 돌려주고, 보낸 몫은 요청 future의 `whenComplete`에서만 돌려준다.
- **확인**: 대조군 두 개. 반납을 `whenComplete`에서 "보낸 직후"로 옮기자 `aTimedOutRequestKeepsItsSlotsUntilItEnds`와 `unusedSlotsAreGivenBackAtOnce`가 실패했다. claim 전 슬롯 확인을 끄자 `noSlotsMeansNoClaim`이 실패했다.

### F22. 결과 개수가 요청과 다르면 남의 결과로 끝난다 (03a)

- **깨지는 것**: 워커는 TB 결과를 요청 순서로 명령과 짝짓는다. 결과가 하나 모자라거나 남으면 뒤의 명령들이 앞 명령의 결과를 받는다. 성공한 이체가 거절됨으로 끝나거나 그 반대가 된다. 핸들러는 틀린 결과로 업무를 처리한다.
- **재현**: `RelayWorkerTest` › TB 요청이 예외로 끝나거나 결과 개수가 틀리면 › `aReplyWithTheWrongCountWritesNothing`. 이체 둘에 결과 하나가 오면 아무것도 쓰지 않고 둘 다 되돌리며, `malformedReply:2:1`을 알린다.
- **구조**: `RelayWorker`가 결과 개수를 요청 개수와 비교한다(P3a-6). 다르면 모두 `release`한다. 다시 보낼 때는 같은 이체 ID이므로 TB가 이미 기록한 것은 `exists`로 돌아온다. 어댑터는 개수를 검사하지 않는다. 판단은 core에 있다.
- **확인**: 처리를 넣기 전에는 `results.get(i)`가 범위를 벗어나 바퀴가 예외로 끝났다(테스트 실패).

### F23. 응답이 유실된 명령을 새 이체 ID로 다시 보내 돈이 두 번 움직인다 (03a, 열린 장애 O3를 닫음)

- **깨지는 것**: TB는 ID당 한 번만 실행한다. 그래서 같은 명령을 다시 보낼 때 이체 ID가 바뀌면 TB는 그것을 새 이체로 기록한다. 응답이 유실된 첫 요청이 이미 기록됐다면 돈이 두 번 나간다.
- **재현**: `RelayOnTigerBeetleTest` › TB 응답이 유실되면 › `aLostReplyIsRetriedWithTheSameTransferIdAndMovesMoneyOnce`. 첫 요청은 실제 TB에 기록되지만 워커에게는 응답이 가지 않는다(테스트 대역 `ReplyLosingLedger`). 워커가 timeout으로 되돌리고 backoff 뒤 다시 보내면, 두 요청의 이체 ID가 같고, 두 번째 TB 결과는 `Exists`이며, 돈은 500만 움직인다. 연결이 끊긴 경우(`aCutConnectionIsRetriedAfterItIsRestored`, Toxiproxy)도 복구 뒤 적용됨으로 끝나고 돈은 한 번만 움직인다.
- **구조**: 이체 ID는 접수 때 생성기가 내서 행(`transfer_id`)에 박는다(§5.1-5, D7). 워커는 claim한 행의 ID만 보내고 새 ID를 만들지 않는다. TB 쪽의 같은 ID 재전송은 `exists`(같은 본문, A1·A3)나 `id_already_failed`(일시적 실패, PRIOR_FAILURE 알림)로 돌아온다.
- **확인**: 대조군으로 두 번째 요청의 이체 ID를 새로 바꾸자 돈이 1000 움직여 테스트가 실패했다.

### F24. 결과를 쓰는 중 한 명령의 저장소 오류가 같은 배치의 나머지 결과를 버린다 (03a, 리뷰에서 발견)

- **깨지는 것**: 워커는 TB 결과를 받은 뒤 명령마다 따로 쓴다. 그런데 한 명령의 쓰기가 저장소 예외(DB 연결 끊김 등)로 끝났을 때 바퀴 전체가 멈추면, 뒤 명령들은 받은 결과를 남기지 못한 채 lease가 끝날 때까지 처리 중으로 남는다. lease가 끝나면 같은 이체 ID로 다시 보내진다. TB가 기억하는 거절(`exceeds_credits` 등)은 그때 `id_already_failed`로 돌아와, 원래 사유를 잃고 PRIOR_FAILURE로 끝난다. §5.4와 F3·F4가 막으려던 결과가 다른 길로 생긴다.
- **재현**: `RelayWorkerTest` › 결과를 쓰는 중에 저장소가 한 명령에서 실패하면 › `theOtherResultsOfTheBatchAreStillWritten`. 배치는 [pay-1 Created, pay-2 ExceedsCredits]이고, pay-1의 결과를 쓸 때 저장소가 한 번 던진다. pay-2는 `ExceedsCredits`로 거절됨이 되어야 하고, `writeFailed:pay-1`을 알려야 한다.
- **구조**: `RelayWorker.attempt`가 쓰기(제출 기록, 건너뛰기, 결과, 되돌리기) 하나하나의 `RuntimeException`을 잡아 `WorkerEvents.writeFailed`로 알리고 다음 명령으로 넘어간다. 실패한 명령은 lease 만료 뒤 같은 이체 ID로 다시 잡힌다. Error는 삼키지 않는다.
- **확인**: 고치기 전에는 바퀴가 예외로 끝나 테스트가 실패했다.

### F25. lease가 응답 기다림보다 짧아 다른 워커가 같은 명령을 다시 보낸다 (03a, 리뷰에서 발견)

- **깨지는 것**: lease는 claim에서 시작한다. 그래서 제출 기록, 응답 기다림(submit-timeout), 명령마다의 결과 쓰기를 모두 덮어야 한다. lease가 submit-timeout보다 짧으면 첫 워커가 응답을 기다리는 동안 다른 워커가 같은 명령을 다시 잡아 보낸다. fencing 덕분에 돈이 두 번 움직이지는 않는다. 하지만 두 번째 요청은 TB가 기억하는 거절에 `id_already_failed`를 받고, 첫 워커의 진짜 결과 쓰기는 0행으로 막혀 원래 사유를 잃는다.
- **재현**: `WorkerSettingsTest` › 잘못 주면 › "lease가 submit-timeout의 두 배보다 짧으면".
- **구조**: `WorkerSettings`는 `lease ≥ 2 × submitTimeout`이 아니면 거부한다. 결과 쓰기는 명령마다 핸들러를 포함한 트랜잭션 하나라서 batch-size에 비례한다. 큰 batch-size에서는 lease를 더 길게 잡아야 한다고 Javadoc에 적었다. 이 몫은 핸들러 속도에 달려 있어 설정 검증으로는 막지 못한다.
- **확인**: 검사를 넣기 전에 그 행은 "예외가 없다"로 실패했다.

### F26. TB 컨테이너가 listening 직후 들어온 TCP 접속에 panic한다 (완화. 열린 장애 O4를 원인까지 좁힘)

- **깨지는 것**: TigerBeetle 0.17.9 컨테이너가 `listening on`을 찍고 2~130ms 뒤 `thread 1 panic: reached unreachable code`(exit 139)로 죽는다. 우리 클라이언트가 붙기 전이다. 테스트 클래스 하나가 통째로 `initializationError`로 실패한다. 2026-10-17에는 tigerbeetle 모듈 실행의 절반 이상에서 났다.
- **재현과 원인 좁히기(2026-10-18, 픽스처와 같은 명령의 `docker run`)**:
  - 포트를 공개하지 않고 띄우기만 하면 50번 중 0번이었다(단독 20번, 3개 동시 기동 30번).
  - 포트를 공개(`-P`)하면 클라이언트가 없는데도 15번 중 2번 났다.
  - 포트를 공개하지 않고, listening 직후 같은 네트워크 네임스페이스에서 `nc -z`로 TCP 접속만 했다 끊으면 30번 중 3번 났다.
  - 같은 탐침을 3초 뒤에 하면 20번 중 0번이었다.
  - 메모리 부족은 아니다. VM에 5.9GiB가 남아 있었고 dmesg에 OOM 기록이 없었다.
  - 결론: listening 직후의 짧은 구간에 들어온 TCP 접속이 TB를 죽인다. 테스트에서 그 접속을 넣는 쪽은 Docker Desktop의 포트 공개 경로로 보인다. 우리 코드는 그 시점을 정할 수 없다. TB 쪽 버그로 보며, TB 소스로 확인하지는 않았다.
- **구조(완화)**: `TigerBeetleContainer.start()`는 이번 시도의 로그에 `listening on`이 찍혔는데 기동이 실패하면 새 컨테이너로 다시 띄운다. 모두 3번 시도하므로 다시 띄우기는 최대 2번이다. 어느 단계가 죽음을 먼저 봤는지(Testcontainers의 기동 검사, 로그 대기, 1초 안정 확인)는 따지지 않는다. 처음 넣은 판정은 "안정 확인이 던진 예외"만 봤다. 그래서 listening 직후 바로 죽어 Testcontainers의 기동 검사가 먼저 보면 다시 띄우지 않았다(전체 리뷰 R-tb-I1, `aDeathSeenByTheStartupCheckIsRetried`로 재현). listening까지 못 간 실패(io_uring 등)는 다시 띄우지 않는다. 시도마다 로그에 `--- TB 시작 시도 n ---` 구분선이 들어가고, 다시 띄울 때마다 표준 오류에 `O4: …다시 띄운다(시도 n)`가 남는다. 프록시 구성(`ProxiedTigerBeetle`)의 TB는 포트를 공개하지 않는다. 클라이언트가 프록시로만 붙으므로 원인 경로 자체가 없다.
- **재현 테스트**: `TigerBeetleContainerGuardTest` › `startRetriesWhenTheServerDiesRightAfterListening`(한 번 죽고 다음엔 사는 가짜 서버. 첫 시도의 panic과 둘째 시도 구분선이 로그에 있다), `aDeathSeenByTheStartupCheckIsRetried`(listening을 찍자마자 죽는 가짜 서버), `startFailsWhenServerDiesRightAfterListening`(매번 죽으면 "3번 시도"와 panic 로그로 실패).
- **확인**: 시도 횟수를 1로 줄이는 대조군에서 두 테스트가 실패했다. 완화를 넣은 뒤 tigerbeetle 모듈을 5번 돌려 실패 0이었다. 다만 그 5번은 실제 TB의 재시도도 0번이었다(자연 발생 없음). 그래서 실제 panic에 대한 효과는 아직 관찰하지 못했다.

### F27. 이미 TB에 보낸 멈춘 명령을 운영자가 "보내지 않음"으로 포기한다 (전체 리뷰에서 발견)

- **깨지는 것**: HALTED는 접수 상태(PENDING)에서만 들어간다. 그런데 접수 상태에는 한 번 보냈다가 되돌린 명령(submit_count > 0)과 TB 결과를 받았는데 핸들러가 실패한 명령(known_result)도 있다. 이런 명령의 참조 예약 행이 사라지면 다음 claim에서 멈춘다. 운영자가 `abandonHalted`로 포기하면 PG는 SKIPPED(ABANDONED_BY_OPERATOR)가 되고, 핸들러는 "적용 안 됨"을 받는다. TB에서는 이미 확정됐을 수 있으므로 돈과 업무 상태가 어긋난다.
- **재현**: `LedgerRelayContract` › 이미 TB에 보낸 뒤 멈춘 명령을 포기하려 하면 › `aReleasedCommandIsRefused`, `aCommandWithAKnownResultIsRefused`(메모리, PG DataSource, PG JPA).
- **구조**: `CommandLifecycle.abandonHalted`는 `submitCount > 0`이거나 `knownResult`가 있으면 `IllegalStateException`("이미 TB에 보낸 적이 있어 포기할 수 없습니다")으로 거부한다. 이런 명령은 TB에서 이체를 확인한 뒤 사람이 정리한다. 운영자 공개 포트(03b)가 이 거부를 그대로 드러낸다.
- **확인**: 넣기 전에 여섯 테스트가 "예외가 없다"로 실패했다.

### F28. 예약이 지워진 뒤 같은 확정을 다시 접수하면 접수증 대신 "예약 없음"이 난다 (전체 리뷰에서 발견)

- **깨지는 것**: `submit`이 참조 검사를 같은 commandId 판정보다 먼저 했다. 보관 작업은 끝난 지 N일 지난 명령을 지운다. 예약은 확정보다 먼저 끝나므로 먼저 지워질 수 있다. 그 뒤 앱이 응답을 놓친 확정을 같은 내용으로 다시 접수하면, 원래 접수증(`LedgerRelay.submit`의 약속) 대신 `ReservationNotFoundException`이 난다. 내용이 다른 재전송도 `CommandConflictException`이 아니라 "예약 없음"이 된다.
- **재현**: `LedgerRelayContract` › 확정이 끝난 뒤 참조 예약 행이 사라진 상태에서 같은 확정을 다시 접수하면 › `resubmittingAFinishedPostAfterItsReservationIsGoneReturnsTheReceipt`, `resubmittingItWithOtherContentIsAConflict`.
- **구조**: 순번을 받은 뒤 같은 commandId를 먼저 찾는다. 있으면 접수증이나 충돌로 끝내고 참조 검사는 하지 않는다(스펙 §5.1 단계 3·4, P2d-2). 경쟁 경로(`insertIfAbsent`가 다른 트랜잭션의 행을 돌려줌)는 그대로 해시를 비교한다.
- **확인**: 바꾸기 전에 두 테스트가 `ReservationNotFoundException`으로 실패했다.

### F29. 데이터가 어긋난 행 하나가 claim 트랜잭션 전체를 던져 릴레이가 멈춘다 (전체 리뷰에서 발견)

- **깨지는 것**: claim은 후보마다 게이트와 상태 기계를 같은 트랜잭션 안에서 돌린다. 한 후보의 데이터가 규칙과 어긋나면 그 판단이 예외를 던진다. 예를 들어 참조 예약이 비어 있는 확정 행(DB 직접 수정)이 있으면 `Optional.orElseThrow`가 던진다. 그러면 claim 전체가 롤백되고 워커는 매 바퀴 같은 예외로 끝나, 모든 키가 멈춘다. "그 키만 막힌다"(§3)에 어긋난다.
- **재현**: `LedgerRelayContract` › 데이터가 어긋난 행이 claim 후보가 되면 › `aMalformedRowIsHaltedAndOtherKeysKeepFlowing`. 다른 키의 pay-2는 잡히고 이상한 행은 멈춘 명령으로 실린다.
- **구조**: `CommandLifecycle.claim`이 후보 하나의 판단 예외를 잡아 그 행을 HALTED로 주차한다. 판단은 순수 Java라 PG 트랜잭션은 멀쩡하다. 주차도 못 하면(저장소가 끝난 행을 후보로 낸 경우 등) 그 후보를 건너뛴다. 어느 쪽이든 이유(원래 예외)를 `ClaimBatch.problems`에 싣고, 워커가 `WorkerEvents.malformedCandidate`로 알린다(최종 리뷰에서 보강: 처음에는 예외를 버렸고 건너뛰는 경우는 아무 신호도 없었다. `ClaimIsolationTest`). 저장소가 행을 **읽는** 단계(결과 코드 저장 이름 해석)에서 나는 이상은 이 격리 밖이다. 그래서 `known_result`·`result_code`에 저장 이름 68개만 받는 CHECK를 둔다(`anUnknownResultCodeIsRefused`, `everyWireNameIsAccepted`). 그 밖의 이런 행을 막는 DB CHECK는 F31에 있다. 운영자는 멈춘 명령 목록(03b)으로 찾는다.
- **확인**: 넣기 전에는 claim이 `NoSuchElementException`으로 끝나 테스트가 실패했다.

### F30. 긴 commandId·순서 키가 접수를 SQL 오류로 깬다 (전체 리뷰에서 발견)

- **깨지는 것**: `command_id`와 `ordering_key`는 b-tree 인덱스가 걸린 `text`다. PG는 약 2.7KB를 넘는 인덱스 항목을 거부한다. 앱이 그보다 긴 ID를 보내면 순번(`lockKeyAndTakeSeq`)의 upsert가 "index row size exceeds maximum"으로 끝나고 앱 트랜잭션 전체가 중단된다. F20과 같은 종류다.
- **재현**: `LedgerCommandTest` › 규칙을 어긴 명령을 만들면 › "commandId가 UTF-8 513바이트면", "순서 키가 한글 171자(513바이트)면". 512바이트는 담긴다(`aCommandIdOf512BytesIsAccepted`). `isAcceptableId`도 513바이트면 false다.
- **구조**: `LedgerCommand.MAX_ID_BYTES = 512`다. commandId, orderingKey, reservationCommandId에 적용하고, `isAcceptableId`도 같은 상한을 본다.
- **확인**: 넣기 전에 세 테스트가 실패했다.

### F31. 스키마가 ID 비교·이체 ID·행 불변식을 DB 쪽에서 지키지 않는다 (전체 리뷰에서 발견)

- **깨지는 것**:
  - **collation**: `command_id`의 유일 인덱스가 접수의 멱등성을 지킨다. 그런데 이 컬럼이 DB 기본 collation을 쓰면 문제가 된다. OS의 libc collation이 바뀌면(postgres 이미지의 Debian 판 변경, glibc 2.28류 업그레이드) text b-tree 인덱스가 조용히 어긋날 수 있다. 그러면 `ON CONFLICT (command_id)`가 있는 행을 못 찾아, 다시 보낸 접수가 새 이체 ID로 두 번째 행을 만들고 돈이 두 번 나간다.
  - **이체 ID 중복**: `transfer_id`가 겹치면(생성기 버그) 두 번째 명령은 TB에서 `exists`를 받아 남의 이체를 자기 결과로 삼는다.
  - **행 불변식**: 처리 중인데 lease 만료 시각이 없는 행은 영원히 다시 잡히지 않는다. 참조 예약이 없는 확정 행은 F29를 일으킨다. 운영 중 마이그레이션도 문제다. `ADD COLUMN` 뒤 `SELECT c.*`의 서버 준비 문장이 앱 트랜잭션 안에서 `0A000 cached plan must not change result type`으로 실패해, 그 앱 트랜잭션 전체가 중단된다.
- **재현**: `JdbcCommandStoreTest` › jdbc 전용: 스키마가 지키는 것 › `idColumnsCompareByteForByte`(4), `aDuplicateTransferIdIsRefused`, `rowsThatBreakTheInvariantsAreRefused`(3), `findSurvivesAnAddedColumn`. 마지막 것은 앱 트랜잭션 안의 조회로 재현했다. 트랜잭션 밖에서는 pgjdbc가 스스로 다시 준비해 재현되지 않았다.
- **구조**: ID 컬럼은 `COLLATE "C"`로 둔다. 이름 붙은 제약으로 `ledger_command_transfer_id_key UNIQUE`, `ledger_command_seq_check`, `ledger_command_reference_check`, `ledger_command_claimed_lease_check`, `ledger_command_skipped_reason_check`를 둔다. 질의는 공용 컬럼 목록(`JdbcCommandStore.COLUMNS`)을 쓴다. 계약 테스트의 행 빌더는 키마다 다른 이체 ID를 낸다(`TestTransferIds`).
- **확인**: 넣기 전에 9건이 실패했다(collation 4, 이체 ID 1, CHECK 3, 컬럼 추가 1).

### F32. claim 폴링 비용이 막힌 키 뒤의 백로그에 비례한다 (02d 전체 리뷰 R-jdbc-I1, 02e)

- **깨지는 것**: 옛 `lockDue`는 후보마다 "같은 키에 앞선 미완료가 있는가"를 찾았다(`NOT EXISTS`, 실행 계획에서는 Nested Loop Anti Join). 맨 앞이 backoff 중이거나 처리 중인 키 하나 뒤에 대기 명령 10만 건이 쌓이면, 폴링 한 번이 그 10만 건을 하나씩 확인한다. 앞선 미완료 찾기가 10만 번, 약 40만 페이지, 576~680ms였다(기준 0.2ms). 후보가 limit보다 적으면 `LIMIT`이 일찍 멈추지 못하고, 플래너는 대부분 통과한다고 잘못 추정했다. F19의 검사("Rows Removed by Filter")는 이 비용을 보지 못한다. 맨 앞이 backoff 중인 키가 많을 때도 같은 비례가 생긴다. TB 회복 직후 보낼 수 있는 맨 앞이 몰리면 접수 순서(id)로 정렬해야 한다. `FOR UPDATE`가 정렬과 `LIMIT` 사이에 끼어 상위 N개 정렬을 쓰지 못해, 실험에서 21ms가 걸리고 디스크 정렬로 넘어갔다.
- **재현**: `LockDueBoundedWorkTest`의 세 테스트가 limit 128에서 실행 계획 맨 위 노드의 페이지를 1,000 이하로 본다. `aBacklogBehindABlockedKeyIsNotRead`(막힌 키 뒤 대기 2만 건 + 보낼 수 있는 키 100개), `headsWaitingForTheirRetryAreNotRead`(맨 앞이 1시간 backoff인 키 2만 개 + 100개), `manyReadyHeadsStopAtTheLimit`(끝난 이력 5만 건 뒤에 보낼 수 있는 맨 앞 2만 개). 측정은 `LockDueBacklogMeasurementTest`(`./gradlew :ledger-relay-jdbc:measurement`)의 네 장면이다.
- **구조**:
  - 후보 순서는 "보낼 수 있게 된 시각"이다. 접수 명령은 다음 시도 시각, 처리 중 명령은 lease 만료 시각이 그 시각이고, 같으면 접수 순서다(P2e-1). 포트 계약 `CommandStore.lockDue`가 정하고, 메모리와 PG 저장소가 같은 계약 테스트를 지난다. 같은 키 안의 순서는 맨 앞이 하나뿐이라 바뀌지 않는다.
  - jdbc 어댑터는 `ledger_command.is_head`(키에서 지금 차례인 명령인가)와 `ready_at`(보낼 시각, 생성 컬럼)을 둔다(P2e-2).
  - 인덱스는 둘이다(P2e-3). `ledger_command_ready ON (ready_at, id) WHERE is_head AND state IN ('PENDING','CLAIMED')`는 claim이 읽는다. 유일 `ledger_command_one_head ON (ordering_key) WHERE is_head`는 키당 맨 앞 둘을 막는다. `ledger_command_due`는 지웠다.
  - 넣을 때 키에 맨 앞이 없으면 새 명령이 맨 앞이 된다. 명령이 끝나도 표시는 그대로 두고, 다음 claim이 그 키 행을 잡아 다음 미완료로 넘긴다(P2e-7, F33). 처음에는 완료가 키 행의 미완료 개수를 고치며 넘겼으나(P2e-4), 그 잠금이 핸들러와 교착해 바꿨다.
  - DB를 직접 고친 뒤에는 `rebuild-heads.sql`로 다시 계산한다(P2e-5, docs/development.md).
- **확인**:
  - 옛 질의에서 페이지 테스트 둘이 실패했다(2만 건 백로그 61,097페이지·126ms, backoff 맨 앞 2만 개 1,447페이지).
  - 새 순서 계약 테스트 둘(`aRetriedCommandQueuesAtItsRetryTime`, `anExpiredLeaseQueuesAtItsExpiry`)은 메모리와 PG에서 모두 실패했다.
  - 변이 확인:
    - `is_head` 조건을 빼면 jdbc 17건이 실패한다.
    - `ORDER BY c.id`로 바꾸면 순서 계약 2건과 회복 몰림 페이지 테스트(1,993페이지)가 실패한다.
  - 모든 계약 테스트 뒤에 표시가 행과 맞는지 검사한다(`PostgresStorage.assertHeadsConsistent`).
  - 측정(limit 128, 10만 건, 세 번 실행): 기준 112페이지 0.12ms, 백로그 10만 건 121페이지 0.13ms(옛 576~680ms), backoff 맨 앞 10만 개 201페이지 0.10ms, 보낼 수 있는 맨 앞 10만 개 194페이지 0.11ms.
- **남은 것**:
  - DB를 직접 고치고 `rebuild-heads.sql`을 돌리지 않으면 그 키가 후보에서 빠지거나 맨 앞이 아닌 명령이 나갈 수 있다. 스크립트는 접수·claim과 겹치지 않게 `ledger_ordering_key`를 표 잠금한다(`HeadBookkeepingTest.rebuildWaitsForAnOpenSubmit`).
  - 통계가 오래됐거나 일반 계획(generic plan)으로 바뀌어 플래너가 `ledger_command_ready` 대신 다른 인덱스와 정렬을 고르는 경우는 테스트하지 않았다.

### F33. 결과를 쓰는 트랜잭션이 키 행을 쥔 채 핸들러를 돌려 앱의 접수와 교착한다 (02e 최종 리뷰)

- **깨지는 것**: 02e의 첫 구현(P2e-4)에서 완료는 `compareAndSet` 안에서 그 키의 행(`ledger_ordering_key`)을 고쳐 잠갔다. 그 잠금을 커밋까지 쥔 채 핸들러를 불렀다. 흔한 앱 코드가 반대 순서로 잡는다. 앱은 주문 행을 고친 뒤 같은 트랜잭션에서 그 주문의 키에 접수한다(접수는 순번을 받으며 키 행을 잡는다). 핸들러는 결과를 받아 같은 주문 행을 고친다. 앱은 키 행을, 핸들러는 주문 행을 기다려 PG가 한쪽을 끊는다(40P01). 앱 쪽이 끊기면 사용자 요청이 간헐적으로 실패한다. 완료 쪽이 끊기면 핸들러 실패로 backoff 뒤 다시 돈다. 또 어떤 키에 접수를 연 채 오래 머무는 앱 트랜잭션이 있으면 그 키의 완료가 기다렸고, 워커는 결과를 한 건씩 쓰므로 뒤의 다른 키 결과도 함께 멈췄다. 이 잠금은 `compareAndSet`이라는 이름 뒤에 숨어 core 코드에서 보이지 않았다.
- **재현**: `HeadRaceTest` › 앱과 핸들러가 같은 주문 행을 고치면 › `anAppThatMarksTheOrderThenSubmitsDoesNotDeadlockWithTheHandler`. `KeyLockFreeStoreTest`(다른 트랜잭션이 키 행을 쥐어도 저장소의 넣기·찾기·후보 잠그기·결과 쓰기가 기다리지 않는다). `HeadStatementRaceTest`(문장 사이의 경쟁 셋).
- **구조** (P2e-7):
  - 포트를 나눴다. 키 행을 잡는 호출은 `OrderingKeys`(`lockKeyAndTakeSeq`, `passOnFinishedHeads`)에만 있다. `CommandStore`는 키 행을 잡지 않는다. jdbc에서는 `JdbcOrderingKeys`와 `JdbcCommandStore`가 나눠 맡는다.
  - 핸들러를 부르는 워커 쓰기는 `WorkerWrites`로 뗐고, 이 클래스는 `OrderingKeys`를 받지 않는다. `ArchitectureTest.theHandlerPathCannotLockAKey`가 "`onCompleted`를 부르는 클래스는 `OrderingKeys`에 의존하지 않는다"를 지킨다.
  - 완료는 맨 앞 표시를 끝난 명령에 남긴다. claim이 후보를 잠그기 전에 끝난 맨 앞의 키 행을 `FOR UPDATE SKIP LOCKED`로 잡아 다음 미완료로 넘긴다. 앱이 쥔 키는 기다리지 않고 다음 claim으로 미룬다. 잡은 키 행은 값을 그대로 두고 갱신한다. 그래야 그보다 앞서 스냅숏을 잡은 REPEATABLE READ 이상의 앱이 그 키에서 순번을 받을 때 40001로 끝나 재시도한다. 그러지 않으면 그 앱은 옛 스냅숏에서 맨 앞이 있다고 보고 새 명령을 맨 앞으로 세우지 않아 그 키가 영원히 나가지 않는다.
  - 맨 앞 표시를 켜는 두 문장(접수의 `MARK_FIRST_UNFINISHED`, 넘기기의 `MARK_NEXT_HEADS`)은 그 키에 맨 앞이 이미 있으면 아무것도 하지 않는다. READ COMMITTED는 문장마다 스냅숏을 새로 잡으므로, 접수가 표시를 끈 뒤 같은 키의 완료가 커밋되면 맨 앞이 둘이 되어 유일 인덱스가 접수를 깨뜨렸다(23505). 넘기기 쪽에도 같은 틈이 있었다. 넘기기 질의가 스냅숏을 잡은 뒤 다른 워커가 같은 키를 넘기고 커밋하면, 늦은 질의는 그 키 행을 그대로 잡는다(`SKIP LOCKED`는 키 행만 최신으로 다시 확인한다). 그다음 켜기가 결과를 쓰는 중인 새 맨 앞 행을 고치려 기다리면 핸들러와 교착했고, 그사이 새 맨 앞이 끝나면 맨 앞이 둘이 되어 claim이 깨졌다. 끝난 맨 앞 행까지 잠그는 방법(`FOR UPDATE OF k, c`)은 쓰지 않았다. 누가 그 행을 쥐면(예: 예약 행, F13) 키 전체의 넘기기가 미뤄지고, 계약 테스트 `aLockedReservationIsStillReported`가 깨졌다. 셋 다 P2e-7 리뷰가 PG 18.6에서 재현했고, `HeadStatementRaceTest`가 문장 사이에 상대를 커밋시켜 본다.
  - `unfinished_count`를 지웠다. 넘길 키는 `ledger_command_finished_head`(끝났는데 맨 앞인 명령)에서 찾는다. 키마다 다음 미완료를 따로 `min(seq)`로 찾는다. 키들을 묶은 `GROUP BY`는 막힌 키 뒤의 백로그를 모두 읽었다(2만 건에 564페이지, 지금 30페이지).
  - 트랜잭션마다 잡는 행은 docs/development.md "트랜잭션과 잠금"에 표로 둔다.
- **확인**:
  - 고치기 전: 교착 테스트가 `deadlock detected`로 끝났고(완료 쪽이 끊겨 핸들러 실패), `KeyLockFreeStoreTest`의 결과 쓰기와 넣기가 2초 안에 끝나지 않았다.
  - 변이 확인:
    - 결과 쓰기에 키 행 잠금을 되살리면 6건이 실패한다(교착, 기다리지 않음 4, 저장소 1).
    - 넘기기를 비우면 24건이 실패한다.
    - 키 행 갱신을 빼면 RR·SERIALIZABLE 스냅숏 테스트 2건이 실패한다.
    - `SKIP LOCKED`를 빼면 "앱이 쥔 키만 미룬다" 테스트가 실패한다.
    - `WorkerWrites`에 `OrderingKeys` 필드를 넣으면 ArchUnit 규칙이 실패한다.
    - `ledger_command_finished_head`를 지우면 넘기기 페이지 테스트가 실패한다.
    - 접수의 켜기에서 "맨 앞이 이미 있으면" 조건을 빼면 `HeadStatementRaceTest`의 접수 경쟁이 23505로 실패한다. 넘기기의 켜기에서 빼면 늦은 넘기기 두 테스트가 실패한다(기다림, 23505).
  - 측정(limit 128): 끝난 이력 5만 건 뒤 끝난 맨 앞 2만 개에서 넘길 키를 잠그는 데 519페이지, 0.19ms.
- **남은 것**:
  - 핸들러 안에서 접수하면 그 결과 쓰기 트랜잭션이 키 행을 잡는다. 접수한 뒤 주문 행을 고치는 핸들러는 "주문 행 → 접수" 앱과 교착할 수 있다. 막지 않고 docs/development.md "트랜잭션과 잠금"에 순서(주문 행을 먼저)를 적는다. 예약이 적용되면 확정을 접수하는 핸들러가 정당한 쓰임이라서다.
  - 앱 트랜잭션끼리 여러 키를 서로 다른 순서로 접수하면 키 행끼리 교착한다. 이것은 02b부터 있던 성질이다(4절).
  - REPEATABLE READ 이상의 앱은 claim의 넘기기와 겹친 접수에서 40001을 받을 수 있다. 앱은 이미 같은 키의 동시 접수에서 이 실패를 재시도해야 한다(4절).
  - 앱이 키 행을 오래 쥐면 그 키의 다음 명령은 그 앱이 커밋할 때까지 나가지 않는다. 다른 키는 영향이 없다.

### F34. 워커의 in-flight 슬롯이 새거나, 설정의 상한과 다른 슬롯으로 돈다 (02d 리뷰 I5·I7, 03b)

- **깨지는 것**:
  - `LedgerClient.submit`이 future 대신 `null`을 돌려주면, 그 배치의 슬롯을 future에 맡긴 직후 `NullPointerException`이 나 슬롯이 영영 돌아오지 않았다. 몇 번 반복되면 슬롯이 바닥나 그 프로세스의 claim이 멈춘다.
  - `RelayWorker`는 `WorkerSettings.maxInFlight`와 `InFlightLimit`을 따로 받았다. 둘이 다르면 설정을 바꿔도 실제 동시 요청 수가 바뀌지 않는다.
- **재현**: `RelayWorkerTest` › TB 클라이언트가 future 대신 null을 주면 › `aNullReplyGivesTheSlotsBackAndReleasesTheBatch`. 설정과 슬롯이 어긋나면 › `aWorkerRefusesSlotsThatDisagreeWithMaxInFlight`.
- **구조**: `null`은 보내는 자리에서 던진 실패와 같은 경로(알리고, 되돌리고, 슬롯 반납)를 탄다. `InFlightLimit`은 만든 슬롯 수(`capacity()`)를 기억하고, `RelayWorker`는 그것이 `maxInFlight`와 다르면 만들어지지 않는다. Spring 자동 설정은 같은 설정값에서 슬롯을 만든다(`LedgerRelayAutoConfigurationTest.slotsFollowMaxInFlight`).
- **확인**: 고치기 전 null 장은 `NullPointerException`으로, 불일치 장은 "거부가 없음"으로 실패했다. 자동 설정에서 슬롯을 1024로 고정하는 변이는 `slotsFollowMaxInFlight`가 잡는다.

### F35. 핸들러 실패를 기록하는 쓰기마저 실패하면 핸들러 예외가 사라진다 (02d 리뷰 I6, 03b)

- **깨지는 것**: 핸들러가 던지면 그 트랜잭션을 롤백하고 새 트랜잭션에 "받은 결과와 핸들러 실패"를 남긴다(§5.4). 이 두 번째 쓰기가 DB 오류로 실패하면 워커는 DB 오류만 받았다. 처음 원인인 핸들러 예외는 어디에도 남지 않아 운영자가 핸들러 버그를 볼 수 없었다.
- **재현**: `RelayWorkerTest` › 핸들러가 던지고 그 기록마저 실패하면 › `theHandlersFailureSurvivesAFailedRecord`.
- **구조**: 두 번째 쓰기의 예외에 핸들러 예외를 `addSuppressed`로 실어 던진다(`WorkerWrites.finish`). 워커 이벤트 `writeFailed`가 둘을 함께 받는다.
- **확인**: 고치기 전에는 suppressed가 비어 실패했다.

### F36. TB가 끊기면 health가 느려져 앱이 서비스에서 빠진다 (03b 최종 리뷰)

- **깨지는 것**: 첫 health는 요청마다 TB에 조회를 보내고 1초까지 기다렸고, 대기 명령을 모두 셌다. TB가 끊기면 health 한 번이 1초를 넘는다. 오케스트레이터의 probe timeout(Kubernetes 기본 1초)에 걸려 앱이 서비스에서 빠지거나 재시작된다. 그러면 접수까지 멈춘다. TB 없이도 접수를 받는다는 이 라이브러리의 요점과 정반대다. 장애가 길어지면 대기가 커져 health가 더 느려지고, 기다리다 끝난 조회는 TB 클라이언트에 계속 쌓였다.
- **재현**: `LedgerRelayHealthIndicatorTest` › `healthDoesNotWaitForASilentLedger`, `aSilentLedgerIsProbedOnceAtATime`. `LockDueBoundedWorkTest` › 대기를 세면 › `countingAHugeBacklogReadsABoundedNumberOfPages`.
- **구조**: TB 확인은 기다리지 않는다. 조회를 한 번에 하나만 보내고, 마지막 답이 5초 안이면 UP이다. 대기 수는 1,000까지만 세고(`Backlog.countsCapped`), 가장 오래된 대기는 새 인덱스 `ledger_command_waiting (id) WHERE state IN ('PENDING','CLAIMED')`의 첫 항목에서 읽는다.
- **확인**: 고치기 전에는 TB가 답하지 않을 때 health 한 번이 probe 시간만큼 걸렸고, 요청 20번에 조회 20개가 쌓였다. 대기 5만 건에서 세는 질의가 2,856페이지를 읽었다. 상한 없는 세기로 되돌리는 변이는 대기 10만 건에서 7,191페이지로 실패한다(상한 2,500).

### F37. 샘플 앱 두 대가 빈 DB에 동시에 뜨면 한쪽이 스키마를 만들다 죽는다 (03b-2 샘플 쇼핑몰)

- **깨지는 것**: compose는 같은 DB를 쓰는 앱 둘을 함께 띄운다. 둘 다 "표가 없으면 만든다"를 하면 같은 순간에 `CREATE TABLE`이 겹친다. 늦은 쪽이 `duplicate key value violates unique constraint "pg_type_typname_nsp_index"`(23505)로 실패해 기동하지 못한다. 면접관이 `docker compose up`을 처음 했을 때 인스턴스 하나가 죽어 있는 장면이 된다(03b 반목표 "처음 띄울 때 실패").
- **재현**: `ShopBootstrapTest` › 빈 DB에서 › `twoInstancesStartingTogetherApplyTheSchemaOnce`. 빈 database에 두 스레드가 함께 `ShopSchema.apply`를 부른다.
- **구조**: `ShopSchema.apply`는 한 트랜잭션에서 `pg_advisory_xact_lock(764)`를 잡은 뒤, `shop_order`가 없을 때만 라이브러리 `schema.sql`과 `shop-schema.sql`을 적용한다. 늦은 쪽은 앞쪽이 커밋할 때까지 lock에서 기다렸다가 표가 있는 것을 보고 지나간다. 적용은 `SmartInitializingSingleton`에서 하므로 워커 루프가 시작하기 전에 끝난다. 라이브러리는 여전히 스키마를 자동 적용하지 않는다. 이것은 샘플 앱만의 편의다.
- **확인**: lock 줄을 지운 대조군에서 위 테스트가 5번 중 5번 23505로 실패했다.

### F39. TB 클라이언트에 호스트 이름 주소를 주면 앱이 기동하지 못한다 (03b-2 샘플 쇼핑몰)

- **깨지는 것**: TB Java 클라이언트(0.17.9)는 `IPv4:포트`만 받는다. compose 안에서 TB 앞의 Toxiproxy는 `toxiproxy:3001`처럼 이름으로만 찾을 수 있다. 이 값을 그대로 넘기면 `Client`를 만드는 순간 `Replica addresses format is invalid`로 실패해 앱이 뜨지 않는다.
- **재현**: `ShopBootstrapTest` › TB 주소를 정하면 › `theClientRefusesAHostName`(TB의 동작을 고정하는 학습 테스트), `aHostNameBecomesAnIpv4Address`.
- **구조**: 샘플 앱이 `shop.tigerbeetle.address`를 기동할 때 IPv4로 바꿔 `Client` 빈을 직접 만든다(`TigerBeetleClientConfig`). 라이브러리 자동 설정은 앱이 준 `Client`를 쓴다. 라이브러리의 `ledger-relay.tigerbeetle.addresses`도 같은 제약을 받으므로 그 설정에는 IP를 쓴다(docs/development.md).
- **확인**: `localhost:<포트>`로 클라이언트를 만들면 위 메시지로 실패하고, 바꾼 `127.0.0.1:<포트>`로는 조회가 된다(2026-10-18, 테스트 컨테이너).

## 2. 열린 장애

| # | 장애 | 막을 곳 |
|---|---|---|
| O2 | 워커가 어느 지점에서 죽어도 중복 없이 복구되는가(kill -9) | 4단계 E1 |
| O7 | 덤프·복원이나 다른 클러스터로 옮긴 DB에서는 옮겨 온 `accepted_xact`가 새 트랜잭션 ID와 우연히 같아져, 이미 커밋된 예약을 참조한 정상 확정이 `ReservationNotFoundException`으로 거부될 수 있다. 재현 테스트는 두지 않았다. 클러스터 이전을 재현하는 비용이 절차 한 줄보다 크다 | 이전 절차: 복원 직후 `UPDATE ledger_command SET accepted_xact = '0'`(docs/development.md) |

닫힌 열린 장애: O1(행 잠금 동시성)은 F11~F15로, O3(응답 유실 뒤 재전송)은 F23으로, O4(TB 기동 panic)는 F26으로 옮겼다(F26은 완화). O6(연결이 묶이지 않은 접수)은 F18로 막았다. O5(멈춘 명령을 볼 방법이 없음)는 03b의 운영자 포트 `LedgerRelayOperator.haltedCommands`(멈춘 이유와 시각 포함)로 닫았다.

## 3. 데드레터큐를 쓰지 않는 이유

문제 명령을 DLQ로 빼고 같은 키의 다음 명령을 처리하면, 그 키의 원장 순서(G4)가 깨진다. 예를 들어 예약보다 확정이 먼저 가면 잘못 거절된다. 그래서 문제 명령은 그 자리에 두고 **그 키만** 막는다. 다른 키는 계속 처리된다.

| | HALT (F9) | 핸들러 실패 (F3~F5) |
|---|---|---|
| 성격 | 규칙 위반, 버그 신호 | 일시적일 수 있음 |
| 처리 | 바로 멈춤, 재시도 없음 | backoff로 계속 재시도. 그동안 그 키는 막힘 |
| 드러내는 지표 | 멈춘 키 수 | `handler_failures` |
| 사람이 할 일 | 원인 수정 후 `abandonHalted`로 포기 | 핸들러 수정 |

## 4. 격리 수준과 무관한 이유

"동시성 문제면 격리 수준을 SERIALIZABLE로 올리면 되지 않나?"에 대한 답이다.

**잠금으로 못 막는 문제가 있다.** 대표가 write skew다. "당직 의사가 최소 한 명은 남아야 한다"는 규칙에서 두 의사가 동시에 "나 빼고 한 명 이상 남는가?"를 확인하고 둘 다 빠지면 0명이 된다. 각자 자기 행만 고쳤으므로 행 잠금끼리 부딪히지 않는다. 판단의 근거가 "조건을 만족하는 행들의 집합"이고 그 집합을 잠글 행이 없기 때문이다. 이런 문제는 SERIALIZABLE이어야 DB가 잡는다.

**우리 설계는 집합 판단마다 잠글 행을 만들어 두었다**(충돌의 실체화). 그래서 READ COMMITTED에서 잠금만으로 충분하다.

| 집합 판단 | 막는 장치 | 기록 |
|---|---|---|
| 이 commandId가 이미 있나 | 유니크 제약 + `ON CONFLICT DO NOTHING` | F15 |
| 이 키에서 내 순번은 몇 번인가 | 키 행 잠금(커밋까지 유지). 그리고 `UNIQUE (ordering_key, seq)` | F14 |
| 이 키의 맨 앞 명령이 나인가(`NOT EXISTS`) | 키 행 잠금 덕분에 나보다 앞 순번이 나중에 생길 수 없다 | F12 |
| 이 키의 맨 앞 표시(`is_head`)를 누가 갖나 | 표시를 정하는 접수와 claim의 넘기기가 둘 다 키 행을 잡는다. 넘기기는 키 행을 갱신해, 옛 스냅숏으로 접수하는 높은 수준의 앱을 40001로 돌려보낸다 | F32, F33 |
| 이 명령을 내가 가져가도 되나 (행 하나) | `FOR UPDATE OF c SKIP LOCKED` | F12, F13 |
| 결과를 써도 되나 (행 하나) | 상태·토큰 조건부 쓰기 | F2 |

**DB 기본값을 바꾸는 방식은 쓰지 않는다.** `default_transaction_isolation`은 기본값일 뿐이다. 실제 수준은 트랜잭션을 여는 쪽이 매번 정한다. 라이브러리가 DB 전체 설정을 바꾸면 같은 DB를 쓰는 앱의 다른 코드까지 바뀌고, 바꿔도 앱이 트랜잭션마다 덮어쓴다. 그래서 약속은 "어떤 격리 수준에서도 데이터는 깨지지 않는다"이다. "에러가 없다"가 아니다.

**높은 수준에서 바뀌는 것은 기다림이 에러가 된다는 것뿐이다.**

- 워커 단계는 우리가 새로 여는 트랜잭션이라 READ COMMITTED로 명시한다(F17, P2b-16).
- 접수는 앱 트랜잭션에 합류하므로 앱의 수준을 따른다(P2b-17). REPEATABLE READ·SERIALIZABLE에서 같은 키나 같은 commandId의 동시 접수는 늦은 쪽이 직렬화 실패(SQLSTATE 40001)로 끝난다. 행은 하나만 남고 순번은 꼬이지 않는다. 앱은 40001을 보고 재시도한다. 확인: `HigherIsolationSubmitTest`의 세 장, 두 수준. 모두 6건이다(셋째 장은 02c에 더한 "다른 순서 키의 같은 commandId"). READ COMMITTED의 같은 경우는 `ReadCommittedSubmitTest`가 본다(02d). claim이 끝난 맨 앞을 넘긴 키에 그보다 앞선 스냅숏으로 접수해도 40001이다(`HeadRaceTest`, 02e F33).
- 대조군(02b, 앞의 두 장 4건 기준): 순번을 키 잠금 없이 `max(seq)+1`로 내면 4건 중 1건(같은 키, REPEATABLE READ)만 실패한다. 늦은 쪽이 재시도 가능한 40001이 아니라 유일성 위반(23505)으로 끝난다. 나머지 3건이 통과하는 이유는 `UNIQUE (ordering_key, seq)`가 순번 공유를 막고, SERIALIZABLE에서는 PostgreSQL이 동시 트랜잭션의 유일성 위반을 40001로 바꾸기 때문이다. 높은 수준에서는 키 잠금 말고 방어선이 하나 더 있는 셈이다. READ COMMITTED에서 키 잠금이 빠진 것은 F14가 잡는다.

모든 트랜잭션을 SERIALIZABLE로 강제하지 않는 이유: 워커끼리 부딪힐 때마다 재시도가 생겨 처리량이 떨어진다. 이미 잠금으로 막은 문제를 비싸게 한 번 더 막는 셈이다.
- 교착: 한 앱 트랜잭션이 키 K1, K2 순서로 접수하고 다른 트랜잭션이 K2, K1 순서로 접수하면, 두 트랜잭션이 `ledger_ordering_key`의 행 잠금을 서로 기다려 교착(40P01)한다. READ COMMITTED에서도 같다. 한 트랜잭션에서 여러 키를 접수하면 키를 정해진 순서(예: 문자열 순)로 접수한다. 또 긴 앱 트랜잭션은 커밋할 때까지 그 키의 다른 접수를 모두 기다리게 한다(lock_timeout을 앱이 정한다).

