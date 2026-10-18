package io.github.ledgerrelay.core;

/**
 * 결과 반영 핸들러. 앱이 빈으로 하나 등록한다. 명령이 최종 상태로 들어가는 트랜잭션 안에서 실행된다(G6).
 * 빠른 로컬 DB 작업만 하고 외부 부작용을 내지 않는다. 던지면 그 전이는 없던 일이 되고 나중에 다시 실행된다.
 * 헥사고날로는 core가 부르고 앱이 구현하는 나가는 포트지만, 앱이 구현하므로 공개 API에 둔다.
 */
@FunctionalInterface
public interface LedgerOutcomeHandler {
    void onCompleted(CommandStatus outcome);
}
