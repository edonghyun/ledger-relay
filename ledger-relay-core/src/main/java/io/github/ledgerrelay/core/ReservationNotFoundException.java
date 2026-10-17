package io.github.ledgerrelay.core;

/** post·void가 참조한 예약이 같은 순서 키에 접수된 다른 reserve 명령이 아니다(스펙 §5.1-3, P2). */
public class ReservationNotFoundException extends RuntimeException {

    private final String commandId;
    private final String reservationCommandId;

    public ReservationNotFoundException(String commandId, String reservationCommandId, String orderingKey) {
        super(commandId + "가 참조한 예약 " + reservationCommandId + "은(는) 같은 순서 키(" + orderingKey
                + ")에 먼저 접수된 reserve 명령이 아닙니다");
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
