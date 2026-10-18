package io.github.ledgerrelay.core;

import java.util.List;

/**
 * 운영자 포트(O5). 사람이 보고 판단해야 하는 일을 앱의 운영 화면이나 관리 API가 부른다. 모든 메서드는 트랜잭션 밖에서
 * 부른다.
 */
public interface LedgerRelayOperator {

    /**
     * 멈춘(HALTED) 명령을 멈춘 순서(행 순서)로 낸다. 다음 페이지는 마지막 항목의 {@link HaltedCommand#position()}을
     * {@code afterPosition}으로 넘겨 읽는다. 첫 페이지는 0이다.
     *
     * @param limit 1 이상 1000 이하
     * @throws IllegalArgumentException limit이 범위 밖
     */
    List<HaltedCommand> haltedCommands(long afterPosition, int limit);

    /**
     * 멈춘 명령을 포기한다(F9, F27). TB에 보내지 않고 건너뜀으로 끝내며, 같은 트랜잭션에서 핸들러가 '운영자
     * 포기' 사유를 받는다. 핸들러가 실패하면 멈춘 그대로 남고 {@link AbandonOutcome#HANDLER_FAILED}다.
     *
     * @throws CommandNotFoundException 접수한 적 없는 명령
     * @throws CannotAbandonException   멈춘 명령이 아니거나({@code NOT_HALTED}), 이미 TB에 보낸 적이 있다
     *                                  ({@code ALREADY_SUBMITTED})
     */
    AbandonOutcome abandonHalted(String commandId);

    /**
     * 최종 상태로 끝난 명령을 행 순서로 한 페이지 읽어, TB에 실제로 있는 이체와 맞춘다. 읽기만 하고 고치지 않는다.
     * 다음 페이지는 {@link ReconciliationReport#lastPosition()}을 넘겨 읽는다. 첫 페이지는 0이다.
     *
     * @param limit 1 이상 1000 이하
     * @throws IllegalArgumentException limit이 범위 밖
     * @throws io.github.ledgerrelay.core.spi.LedgerUnavailableException TB가 정해진 시간 안에 조회에 답하지 않았다
     */
    ReconciliationReport reconcile(long afterPosition, int limit);
}
