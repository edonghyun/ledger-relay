package io.github.ledgerrelay.core;

/** 명령 종류(스펙 §6). */
public enum CommandKind {
    TRANSFER,
    RESERVE,
    POST,
    VOID;

    /** post와 void는 앞서 접수한 예약 명령을 참조한다. */
    public boolean referencesReservation() {
        return this == POST || this == VOID;
    }
}
