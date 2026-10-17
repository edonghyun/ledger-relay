package io.github.ledgerrelay.core.spi;

import java.util.function.Supplier;

/**
 * 트랜잭션 경계(스펙 G1, G6, §5.4). jdbc는 Spring 트랜잭션으로, 메모리 저장소는 스냅샷으로 구현한다.
 * 서비스는 이 포트로만 트랜잭션을 다룬다. {@link CommandStore}의 메서드는 늘 트랜잭션 안에서 불린다.
 */
public interface Transactions {

    /**
     * 트랜잭션 안에서 work를 실행한다. 활성 트랜잭션이 있으면 합류하고, 없으면 새로 열어 커밋한다(Spring REQUIRED).
     * work가 던지면 새로 연 트랜잭션은 롤백하고, 그 예외를 그대로 던진다.
     */
    <T> T inTransaction(Supplier<T> work);

    /**
     * 호출한 스레드에 활성 트랜잭션이 없으면 던진다(G1). 접수가 앱 트랜잭션에 묶여야 하기 때문이다.
     * 예외 타입은 구현이 정한다. jdbc는 Spring의 IllegalTransactionStateException을 던진다.
     */
    void requireActive();
}
