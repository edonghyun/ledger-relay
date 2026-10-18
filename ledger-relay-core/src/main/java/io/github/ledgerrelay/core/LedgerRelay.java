package io.github.ledgerrelay.core;

import java.util.Optional;

/**
 * ledger-relay의 들어오는 포트(스펙 §6). 앱이 쓰는 것은 이 인터페이스뿐이다.
 * Spring 모듈(3단계)이 구현을 조립해 빈으로 내놓는다.
 */
public interface LedgerRelay {

    /**
     * 명령을 접수한다(§5.1). 앱 트랜잭션 안에서 부른다(G1). 앱 트랜잭션이 롤백되면 접수도 사라진다.
     * 같은 명령을 다시 접수하면 처음 접수증을 그대로 돌려받는다.
     *
     * @throws CommandConflictException     같은 commandId로 다른 내용이 이미 접수됨(G7)
     * @throws ReservationNotFoundException post·void의 참조가 같은 키에 이미 커밋된 reserve 명령이 아님. 같은 앱
     *                                      트랜잭션에서 접수한 예약도 여기에 든다(P2b-1)
     * @throws InvalidCommandException      U+0000·짝 없는 서로게이트가 들었거나 512바이트를 넘는 ID 등 명령을 만들 수 없음
     * @throws RuntimeException             트랜잭션 밖에서 불렀다(G1). 타입은 트랜잭션 어댑터가 정한다: jdbc 어댑터는 Spring의
     *                                      {@code IllegalTransactionStateException}, 메모리 저장소는 {@code IllegalStateException}
     */
    CommandReceipt submit(LedgerCommand command);

    /** 명령의 지금 상태(§4.2). 접수한 적 없으면 비어 있다. */
    Optional<CommandStatus> status(String commandId);
}
