package io.github.ledgerrelay.core.spi;

/**
 * 순서 키 행을 잠그는 저장소. 잠금은 트랜잭션 끝까지 간다. 키 행을 잡는 것은 이 포트뿐이며
 * {@link CommandStore}는 잡지 않는다. 핸들러가 도는 트랜잭션은 이 포트를 쓰지 않는다: 앱은 주문 행을 쥔 채
 * 접수하며 키 행을 기다릴 수 있으므로, 키 행을 쥔 채 핸들러가 주문 행을 기다리면 교착한다.
 */
public interface OrderingKeys {

    /** 순서 키를 트랜잭션 끝까지 잠그고 그 키의 다음 순번을 낸다. 키마다 1부터 오르며, 빈 번호가 생길 수 있다. */
    long lockKeyAndTakeSeq(String orderingKey);

    /**
     * 끝난 명령이 쥔 맨 앞을 그 키의 다음 미완료 명령으로 넘긴다. 다음이 없으면 맨 앞을 없앤다. 최대 limit개 키를 넘기며,
     * 다른 트랜잭션이 잡은 키 행은 기다리지 않고 건너뛴다(다음 호출이 넘긴다). claim이 {@link CommandStore#lockDue} 앞에서
     * 부른다. 맨 앞을 매번 계산하는 저장소에는 할 일이 없다.
     */
    void passOnFinishedHeads(int limit);
}
