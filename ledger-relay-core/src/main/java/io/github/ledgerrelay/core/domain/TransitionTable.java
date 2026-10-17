package io.github.ledgerrelay.core.domain;

import static io.github.ledgerrelay.core.domain.Transition.Effect.BACKOFF;
import static io.github.ledgerrelay.core.domain.Transition.Effect.COUNT_HANDLER_FAILURE;
import static io.github.ledgerrelay.core.domain.Transition.Effect.INCREMENT_LEASE_TOKEN;
import static io.github.ledgerrelay.core.domain.Transition.Effect.KEEP_KNOWN_RESULT;
import static io.github.ledgerrelay.core.domain.Transition.Effect.RECORD_SUBMISSION;
import static io.github.ledgerrelay.core.domain.Transition.Effect.RUN_HANDLER;

/**
 * 스펙 §4.3 전이표. 상태는 앞으로만 진행하고(G5), 최종 상태로 들어갈 때 핸들러를 같은 트랜잭션에서 실행한다(G6).
 * 모든 칸은 {@code TransitionTableTest}의 표와 1:1로 대응한다.
 */
public final class TransitionTable {

    private TransitionTable() {
    }

    public static Transition next(StoredState current, CommandEvent event) {
        return switch (current) {
            case PENDING -> fromPending(event);
            case CLAIMED -> fromClaimed(event);
            case APPLIED, REJECTED, CONFLICT, SKIPPED -> fromFinal(event);
            case HALTED -> fromHalted(event);
        };
    }

    private static Transition fromPending(CommandEvent event) {
        return switch (event) {
            case CLAIM_WITH_GATE_OPEN -> Transition.moveTo(StoredState.CLAIMED, INCREMENT_LEASE_TOKEN);
            case CLAIM_WITH_GATE_CLOSED -> Transition.unchanged();
            // 멈출 명령도 claim처럼 토큰을 올린다. 운영자의 포기가 그 토큰으로 조건부로 쓴다
            case CLAIM_WITH_GATE_HALTED -> Transition.moveTo(StoredState.HALTED, INCREMENT_LEASE_TOKEN);
            case ABANDONED_BY_OPERATOR -> Transition.impossible();
            // 건너뜀도 claim한 뒤에 일어난다. 그래야 lease 토큰으로 fencing되고, 핸들러 실패가 CLAIMED 칸을 탄다.
            case SUBMITTING, RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR,
                    HANDLER_FAILED -> Transition.impossible();
            case LEASE_EXPIRED -> Transition.notApplicable();
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }

    private static Transition fromClaimed(CommandEvent event) {
        return switch (event) {
            // lease가 살아 있는 동안 다시 claim하는 것은 불가. 만료 뒤 재claim은 LEASE_EXPIRED다.
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED, CLAIM_WITH_GATE_HALTED, ABANDONED_BY_OPERATOR ->
                    Transition.impossible();
            case SUBMITTING -> Transition.moveTo(StoredState.CLAIMED, RECORD_SUBMISSION);
            case RESERVATION_NOT_APPLIED -> Transition.moveTo(StoredState.SKIPPED, RUN_HANDLER);
            case TB_APPLIED -> Transition.moveTo(StoredState.APPLIED, RUN_HANDLER);
            case TB_REJECTED -> Transition.moveTo(StoredState.REJECTED, RUN_HANDLER);
            case TB_CONFLICT -> Transition.moveTo(StoredState.CONFLICT, RUN_HANDLER);
            case NO_REPLY_OR_ERROR -> Transition.moveTo(StoredState.PENDING, BACKOFF);
            case HANDLER_FAILED -> Transition.moveTo(StoredState.PENDING, BACKOFF, KEEP_KNOWN_RESULT, COUNT_HANDLER_FAILURE);
            case LEASE_EXPIRED -> Transition.moveTo(StoredState.CLAIMED, INCREMENT_LEASE_TOKEN);
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }

    private static Transition fromFinal(CommandEvent event) {
        return switch (event) {
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED, CLAIM_WITH_GATE_HALTED, ABANDONED_BY_OPERATOR ->
                    Transition.impossible();
            case SUBMITTING, RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR,
                    STALE_TOKEN_WRITE -> Transition.zeroRows();
            case HANDLER_FAILED, LEASE_EXPIRED -> Transition.notApplicable();
        };
    }

    /** 멈춘 명령은 claim 후보가 아니고, 운영자의 포기만 받는다(§5.2 HALT). */
    private static Transition fromHalted(CommandEvent event) {
        return switch (event) {
            case ABANDONED_BY_OPERATOR -> Transition.moveTo(StoredState.SKIPPED, RUN_HANDLER);
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED, CLAIM_WITH_GATE_HALTED, SUBMITTING,
                    RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR ->
                    Transition.impossible();
            // 포기할 때 핸들러가 실패하면 롤백되어 멈춘 그대로 남는다. 받은 TB 결과가 없으므로 기록할 것도 없다
            case HANDLER_FAILED, LEASE_EXPIRED -> Transition.notApplicable();
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }
}
