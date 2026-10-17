package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.SkipReason;
import java.util.Optional;

/**
 * 토큰까지 다루는 상태 기계. 애플리케이션 서비스가 실행하며, 무작위 검증(E6a)의 오라클이기도 하다.
 *
 * <p>claim은 토큰을 받는다. 나머지 쓰기는 claim 때 받은 토큰을 내밀고, 행이 처리 중(CLAIMED)이며 토큰이 같을
 * 때만 반영된다. 아니면 0행이다(G5). 반영되는 쓰기의 목적지와 효과는 전이표(§4.3)의 CLAIMED 줄이 정한다.
 * 전이표가 "불가"나 "해당 없음"으로 적은 칸에 닿으면 모듈의 버그이므로 {@link IllegalStateException}을 던진다.
 */
public final class CommandStateMachine {

    private CommandStateMachine() {
    }

    public static StepResult apply(CommandRow row, Step step) {
        return switch (step) {
            case Step.Claim claim -> claim(row, claim);
            case Step.Write write -> write(row, write);
            case Step.Abandon abandon -> follow(row, CommandEvent.ABANDONED_BY_OPERATOR, abandon);
        };
    }

    private static StepResult claim(CommandRow row, Step.Claim claim) {
        CommandEvent event = switch (row.state()) {
            case PENDING -> claim.gate().claimEvent();
            // 처리 중 명령은 lease가 끝났을 때만 다시 claim된다. 살아 있으면 표의 "불가" 칸으로 간다.
            // 게이트 결정은 보지 않는다. 처리 중 명령은 claim될 때 이미 게이트를 통과했고, 게이트 입력(같은 키의 앞 명령,
            // 참조한 예약)은 그 뒤 앞으로만 움직인다. 이 행이 멈춤 대상이 되려면 DB를 직접 고쳐야 한다.
            case CLAIMED -> claim.leaseExpired() ? CommandEvent.LEASE_EXPIRED : CommandEvent.CLAIM_WITH_GATE_OPEN;
            case APPLIED, REJECTED, CONFLICT, SKIPPED, HALTED -> CommandEvent.CLAIM_WITH_GATE_OPEN;
        };
        return follow(row, event, claim);
    }

    private static StepResult write(CommandRow row, Step.Write write) {
        // jdbc: UPDATE … WHERE id = ? AND lease_token = ? AND state = 'CLAIMED'
        if (row.state() != StoredState.CLAIMED || row.leaseToken() != write.token()) {
            return new StepResult.Fenced();
        }
        CommandEvent event = switch (write) {
            case Step.MarkSubmitted submitted -> {
                if (row.knownResult().isPresent()) {
                    throw bug("이미 아는 결과(" + row.knownResult().get() + ")가 있는 명령을 TB에 다시 보내려 한다. "
                            + "다시 보내면 원래 사유가 IdAlreadyFailed로 덮인다(§5.4)");
                }
                yield CommandEvent.SUBMITTING;
            }
            case Step.Complete complete -> {
                if (row.knownResult().isPresent() && row.knownResult().get() != complete.code()) {
                    throw bug("이미 아는 결과는 " + row.knownResult().get() + "인데 " + complete.code() + "로 끝내려 한다");
                }
                yield CommandEvent.of(complete.code().category());
            }
            case Step.Skip skip -> CommandEvent.RESERVATION_NOT_APPLIED;
            case Step.Release release -> CommandEvent.NO_REPLY_OR_ERROR;
            case Step.HandlerFailed failed -> CommandEvent.HANDLER_FAILED;
        };
        return follow(row, event, write);
    }

    private static StepResult follow(CommandRow row, CommandEvent event, Step step) {
        Transition transition = TransitionTable.next(row.state(), event);
        return switch (transition.outcome()) {
            case MOVE -> new StepResult.Moved(moved(row, transition, step), transition.effects());
            case UNCHANGED -> new StepResult.Unchanged();
            case ZERO_ROWS -> new StepResult.Fenced();
            case IMPOSSIBLE, NOT_APPLICABLE ->
                    throw bug(row.state() + " 상태에서 " + event + " 사건은 일어날 수 없다(§4.3 " + transition.outcome() + ")");
        };
    }

    private static CommandRow moved(CommandRow row, Transition transition, Step step) {
        long token = row.leaseToken();
        long submitCount = row.submitCount();
        Optional<LedgerResultCode> knownResult = row.knownResult();
        int handlerFailures = row.handlerFailures();
        for (Transition.Effect effect : transition.effects()) {
            switch (effect) {
                case INCREMENT_LEASE_TOKEN -> token++;
                case RECORD_SUBMISSION -> submitCount++;
                case KEEP_KNOWN_RESULT -> knownResult = ((Step.HandlerFailed) step).knownResult();
                case COUNT_HANDLER_FAILURE -> handlerFailures++;
                // 서비스가 맡는다: 같은 트랜잭션의 핸들러 실행, 저장소 시계(PG now()) 기준의 다음 시도 시각
                case RUN_HANDLER, BACKOFF -> { }
            }
        }
        Optional<LedgerResultCode> resultCode = step instanceof Step.Complete complete
                ? Optional.of(complete.code())
                : row.resultCode();
        Optional<SkipReason> skipReason = switch (step) {
            case Step.Skip skip -> Optional.of(SkipReason.RESERVATION_NOT_APPLIED);
            case Step.Abandon abandon -> Optional.of(SkipReason.ABANDONED_BY_OPERATOR);
            default -> row.skipReason();
        };
        return new CommandRow(transition.to(), token, submitCount, knownResult, resultCode, handlerFailures, skipReason);
    }

    private static IllegalStateException bug(String what) {
        return new IllegalStateException("버그: " + what);
    }
}
