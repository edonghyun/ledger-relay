package io.github.ledgerrelay.core;

import static io.github.ledgerrelay.core.Transition.Effect.BACKOFF;
import static io.github.ledgerrelay.core.Transition.Effect.INCREMENT_LEASE_TOKEN;
import static io.github.ledgerrelay.core.Transition.Effect.KEEP_KNOWN_RESULT;
import static io.github.ledgerrelay.core.Transition.Effect.RUN_HANDLER;

/**
 * 스펙 §4.3 전이표. 상태는 앞으로만 진행하고(G5), 최종 상태로 들어갈 때 핸들러를 같은 트랜잭션에서 실행한다(G6).
 * 모든 칸은 {@code TransitionTableTest}의 표와 1:1로 대응한다.
 */
public final class TransitionTable {

    private TransitionTable() {
    }

    public static Transition next(CommandState current, CommandEvent event) {
        return switch (current) {
            case PENDING -> fromPending(event);
            case CLAIMED -> fromClaimed(event);
            case APPLIED, REJECTED, CONFLICT, SKIPPED -> fromFinal(event);
        };
    }

    private static Transition fromPending(CommandEvent event) {
        return switch (event) {
            case CLAIM_WITH_GATE_OPEN -> Transition.moveTo(CommandState.CLAIMED, INCREMENT_LEASE_TOKEN);
            case CLAIM_WITH_GATE_CLOSED -> Transition.unchanged();
            // 건너뜀도 claim한 뒤에 일어난다. 그래야 lease 토큰으로 fencing되고, 핸들러 실패가 CLAIMED 칸을 탄다.
            case RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR, HANDLER_FAILED ->
                    Transition.impossible();
            case LEASE_EXPIRED -> Transition.notApplicable();
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }

    private static Transition fromClaimed(CommandEvent event) {
        return switch (event) {
            // lease가 살아 있는 동안 다시 claim하는 것은 불가. 만료 뒤 재claim은 LEASE_EXPIRED다.
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED -> Transition.impossible();
            case RESERVATION_NOT_APPLIED -> Transition.moveTo(CommandState.SKIPPED, RUN_HANDLER);
            case TB_APPLIED -> Transition.moveTo(CommandState.APPLIED, RUN_HANDLER);
            case TB_REJECTED -> Transition.moveTo(CommandState.REJECTED, RUN_HANDLER);
            case TB_CONFLICT -> Transition.moveTo(CommandState.CONFLICT, RUN_HANDLER);
            case NO_REPLY_OR_ERROR -> Transition.moveTo(CommandState.PENDING, BACKOFF);
            case HANDLER_FAILED -> Transition.moveTo(CommandState.PENDING, BACKOFF, KEEP_KNOWN_RESULT);
            case LEASE_EXPIRED -> Transition.moveTo(CommandState.CLAIMED, INCREMENT_LEASE_TOKEN);
            case STALE_TOKEN_WRITE -> Transition.zeroRows();
        };
    }

    private static Transition fromFinal(CommandEvent event) {
        return switch (event) {
            case CLAIM_WITH_GATE_OPEN, CLAIM_WITH_GATE_CLOSED -> Transition.impossible();
            case RESERVATION_NOT_APPLIED, TB_APPLIED, TB_REJECTED, TB_CONFLICT, NO_REPLY_OR_ERROR, STALE_TOKEN_WRITE ->
                    Transition.zeroRows();
            case HANDLER_FAILED, LEASE_EXPIRED -> Transition.notApplicable();
        };
    }
}
