package io.github.ledgerrelay.core;

import java.util.List;
import java.util.Optional;

/**
 * 순서 게이트(스펙 G4, §5.2). 같은 순서 키에서 seq가 더 작은 명령이 모두 최종 상태일 때만 제출한다.
 * post·void는 참조한 예약이 APPLIED가 아닌 최종 상태로 끝났으면 건너뛴다.
 *
 * <p>2단계 jdbc는 이 규칙을 SQL로 옮긴다. 이 클래스가 그 규칙의 명세이며, 가짜 TB와 무작위 검증(E6a)이 직접 쓴다.
 */
public final class OrderingGate {

    private OrderingGate() {
    }

    /**
     * @param candidate 제출하려는 명령
     * @param candidateSeq 그 명령의 키 내 순번
     * @param sameKey 같은 순서 키의 명령들. 순서는 상관없다. 후보 자신이 들어 있어도 되고,
     *                보관 정책으로 지워진 명령은 없을 수 있다.
     */
    public static GateDecision decide(LedgerCommand candidate, long candidateSeq, List<QueuedCommand> sameKey) {
        boolean earlierUnfinished = sameKey.stream()
                .anyMatch(q -> q.seq() < candidateSeq && !q.state().isFinal());
        if (earlierUnfinished) {
            return GateDecision.CLOSED;
        }
        if (!candidate.kind().referencesReservation()) {
            return GateDecision.OPEN;
        }
        String reservationId = candidate.reservation().orElseThrow();
        Optional<QueuedCommand> reservation = sameKey.stream()
                .filter(q -> q.commandId().equals(reservationId))
                .findFirst();
        if (reservation.isEmpty()) {
            // 접수 때 참조 검사(§5.1)를 통과했으니 존재했던 예약이다. 보관 정책으로 지워졌으면 TB가 판정한다(P1-8).
            return GateDecision.OPEN;
        }
        CommandState state = reservation.get().state();
        if (state == CommandState.APPLIED) {
            return GateDecision.OPEN;
        }
        if (state.isFinal()) {
            return GateDecision.SKIP;
        }
        // 예약이 미완료인데 위의 earlierUnfinished에 걸리지 않았다면 예약 seq가 후보보다 크거나 같다.
        // 정상 경로에서는 나올 수 없는 입력이므로 제출하지 않는다.
        return GateDecision.CLOSED;
    }
}
