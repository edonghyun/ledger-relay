package io.github.ledgerrelay.core.spi;

import java.util.List;
import java.util.Optional;

/**
 * 명령 저장소(스펙 §5.1~§5.4, §5.6). 저장과 조회만 한다. 무엇을 쓸지는 서비스(core.application)가 도메인 규칙으로
 * 정한다. 모든 메서드는 {@link Transactions}가 연 트랜잭션 안에서 불린다. 계약은 testFixtures의
 * {@code CommandStoreContract}가 정한다.
 */
public interface CommandStore {

    /** 순서 키를 트랜잭션 끝까지 잠그고 그 키의 다음 순번을 낸다(§5.1-2). 키마다 1부터 오르며, 빈 번호가 생길 수 있다. */
    long nextSeq(String orderingKey);

    /** 넣는다. 같은 commandId의 행이 이미 있으면 넣지 않고 있던 행을 돌려준다. 넣었으면 비어 있다. */
    Optional<CommandRecord> insertIfAbsent(NewCommand command);

    Optional<CommandRecord> find(String commandId);

    /**
     * claim 후보를 접수 순서로 limit개까지 잠그고 돌려준다(§5.2). 후보는 순서 키마다 맨 앞의 미완료 명령이며,
     * 접수 상태면 다음 시도 시각이 지났고, 처리 중이면 lease가 끝난 것이다. 다른 트랜잭션이 잠근 행은 건너뛴다.
     * 맨 앞 명령만 내는 것은 막힌 키의 뒤 명령들이 limit을 차지하지 않게 하기 위해서다(P2a-19).
     */
    List<DueCommand> lockDue(int limit);

    /** 행이 있고 상태와 토큰이 기대와 같으면 바꾸고 true. 아니면 아무것도 바꾸지 않고 false. */
    boolean compareAndSet(RowChange change);
}
