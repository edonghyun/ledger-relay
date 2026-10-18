package io.github.ledgerrelay.core;

/** 포기할 수 없는 명령이다. 이유는 {@link #reason()}. */
public class CannotAbandonException extends RuntimeException {

    /** 포기할 수 없는 이유. */
    public enum Reason {
        /** 멈춘(HALTED) 명령이 아니다. */
        NOT_HALTED,
        /** 이미 TB에 보낸 적이 있어 TB에서 확정됐을 수 있다(F27). TB에서 이체를 확인한 뒤 수동으로 정리한다. */
        ALREADY_SUBMITTED
    }

    private final String commandId;
    private final Reason reason;

    public CannotAbandonException(String commandId, Reason reason, String message) {
        super(message);
        this.commandId = commandId;
        this.reason = reason;
    }

    public String commandId() {
        return commandId;
    }

    public Reason reason() {
        return reason;
    }
}
