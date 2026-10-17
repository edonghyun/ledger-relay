package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 명령 하나의 조회 결과이자, 결과 반영 핸들러가 받는 값(스펙 §6).
 * {@link #state()}는 조회 상태(§4.2)다. 거절·충돌로 끝났을 때만 {@link #rejection()}이 있다.
 */
public record CommandStatus(
        String commandId,
        State state,
        Optional<Rejection> rejection,
        BigInteger transferId,
        long submitCount,
        Instant acceptedAt,
        Optional<Instant> completedAt) {

    /** 조회 상태(스펙 §4.2). */
    public enum State {
        /** 접수됐고 아직 TB에 보낸 적이 없다. */
        ACCEPTED,
        /** TB에 보낸 적이 있고 결과를 기다린다. 응답이 유실된 미확정도 여기에 든다. */
        IN_PROGRESS,
        APPLIED,
        REJECTED,
        CONFLICT,
        SKIPPED;

        public boolean isFinal() {
            return this != ACCEPTED && this != IN_PROGRESS;
        }
    }

    public CommandStatus {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rejection, "rejection");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        boolean needsReason = state == State.REJECTED || state == State.CONFLICT;
        if (rejection.isPresent() != needsReason) {
            throw new IllegalArgumentException(
                    "rejection은 REJECTED·CONFLICT일 때만, 그리고 그때는 반드시 있어야 합니다: state=" + state + ", rejection=" + rejection);
        }
        rejection.ifPresent(reason -> {
            // 분류가 보내는 최종 상태(§4.4). 공개 API는 domain을 모르므로 여기서 다시 판정하고,
            // domain의 StoredState.finalStateFor와 같은지는 StoredStateTest가 확인한다.
            State expected = switch (reason.category()) {
                case CONFLICT, UNSUPPORTED -> State.CONFLICT;
                case BUSINESS, PRIOR_FAILURE, INVALID -> State.REJECTED;
                case APPLIED -> State.APPLIED;
            };
            if (expected != state) {
                throw new IllegalArgumentException(reason.tbCode() + "(" + reason.category() + ")는 " + state + " 상태의 사유가 아닙니다");
            }
        });
        if (completedAt.isPresent() != state.isFinal()) {
            throw new IllegalArgumentException("completedAt은 끝난 명령에만, 그리고 반드시 있어야 합니다: state=" + state);
        }
    }
}
