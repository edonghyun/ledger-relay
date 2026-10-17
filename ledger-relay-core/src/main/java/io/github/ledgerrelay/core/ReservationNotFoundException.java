package io.github.ledgerrelay.core;

/**
 * post·void가 참조한 예약이 같은 순서 키에 이미 커밋된 reserve 명령이 아니다(스펙 §5.1-4, P2). 같은 앱 트랜잭션에서 방금 접수한
 * 예약도 아직 커밋되지 않았으므로 여기에 든다(02b P2b-1).
 */
public class ReservationNotFoundException extends RuntimeException {

    private final String commandId;
    private final String reservationCommandId;

    public ReservationNotFoundException(String commandId, String reservationCommandId, String orderingKey) {
        super(commandId + "가 참조한 예약 " + reservationCommandId + "은(는) 같은 순서 키(" + orderingKey
                + ")에 이미 커밋된 reserve 명령이 아닙니다");
        this.commandId = commandId;
        this.reservationCommandId = reservationCommandId;
    }

    public String commandId() {
        return commandId;
    }

    public String reservationCommandId() {
        return reservationCommandId;
    }
}
