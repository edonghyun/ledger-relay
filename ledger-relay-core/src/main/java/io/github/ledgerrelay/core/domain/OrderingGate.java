package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerCommand;
import java.util.List;
import java.util.Optional;

/**
 * 순서 게이트(스펙 G4, §5.2). 같은 순서 키에서 seq가 더 작은 명령이 모두 최종 상태일 때만 제출한다.
 * post·void는 참조한 예약이 APPLIED가 아닌 최종 상태로 끝났으면 건너뛴다.
 *
 * <p>애플리케이션 서비스가 claim할 때 이 규칙을 실행한다. 서비스는 {@code sameKey}로 참조한 예약 하나만 넘긴다. 키마다 맨 앞만
 * 후보로 내는 것은 저장소 계약({@code CommandStoreContract})이 지키므로, 앞 미완료 명령 검사({@link GateDecision#CLOSED})는 그
 * 계약이 깨졌을 때를 위한 것이다(02d P2d-10).
 */
public final class OrderingGate {

    private OrderingGate() {
    }

    /**
     * @param candidate 제출하려는 명령
     * @param candidateSeq 그 명령의 키 내 순번
     * @param sameKey 같은 순서 키의 명령들(서비스는 참조한 예약만 넘긴다). 순서는 상관없다. 후보 자신이 들어 있어도 되고,
     *                보관 정책으로 지워진 명령은 없을 수 있다.
     */
    public static GateDecision decide(LedgerCommand candidate, long candidateSeq, List<QueuedCommand> sameKey) {
        return decide(candidate.kind(), candidate.reservation(), candidateSeq, sameKey);
    }

    /**
     * @param kind                 제출하려는 명령의 종류
     * @param reservationCommandId post·void가 참조한 예약. 그 밖에는 비어 있다
     * @param candidateSeq         그 명령의 키 내 순번
     * @param sameKey              같은 순서 키의 명령들(서비스는 참조한 예약만 넘긴다). 순서는 상관없다. 후보 자신이 들어 있어도 되고,
     *                             보관 정책으로 지워진 명령은 없을 수 있다.
     */
    public static GateDecision decide(CommandKind kind, Optional<String> reservationCommandId, long candidateSeq,
            List<QueuedCommand> sameKey) {
        boolean earlierUnfinished = sameKey.stream()
                .anyMatch(q -> q.seq() < candidateSeq && !q.state().isFinal());
        if (earlierUnfinished) {
            return GateDecision.CLOSED;
        }
        if (!kind.referencesReservation()) {
            return GateDecision.OPEN;
        }
        String reservationId = reservationCommandId.orElseThrow();
        Optional<QueuedCommand> reservation = sameKey.stream()
                .filter(q -> q.commandId().equals(reservationId))
                .findFirst();
        if (reservation.isEmpty()) {
            // 접수 때 참조 검사(§5.1)를 통과했고, 보관 정책은 미완료 post·void가 참조하는 예약을 지우지 않는다(§5.6).
            // 그런데도 없다면 버그나 DB 직접 수정이다. 예약이 충돌(CONFLICT)로 끝났다면 같은 TB ID에 남의 이체가 있어,
            // 보내면 그 이체를 확정할 수 있다. 저장소가 잠긴 예약을 못 읽은 경우라면 확정이 예약보다 먼저 간다.
            // 어느 쪽이든 TB에 보내지 않고 멈춘다(docs/failure-modes.md F10).
            return GateDecision.HALT;
        }
        StoredState state = reservation.get().state();
        if (state == StoredState.APPLIED) {
            return GateDecision.OPEN;
        }
        if (state.isFinal()) {
            return GateDecision.SKIP;
        }
        // 예약이 미완료인데 위의 earlierUnfinished에 걸리지 않았다면 예약 seq가 후보보다 크거나 같다.
        // 접수 검사가 막았어야 하는 순서다. 조용히 기다리면 키가 영원히 멈추므로 멈추고 알린다.
        return GateDecision.HALT;
    }
}
