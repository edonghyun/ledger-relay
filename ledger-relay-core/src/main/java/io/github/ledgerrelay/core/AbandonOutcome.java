package io.github.ledgerrelay.core;

/** 멈춘 명령을 포기한 결과. */
public enum AbandonOutcome {
    /** 건너뜀으로 끝났고 핸들러도 끝까지 실행됐다. */
    ABANDONED,
    /** 핸들러가 던져 롤백됐다. 명령은 멈춘 그대로이고, 다시 포기할 수 있다. */
    HANDLER_FAILED
}
