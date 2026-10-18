package io.github.ledgerrelay.core;

/** 빌더로 만든 명령이 규칙을 어겼다. 메시지는 무엇이 틀렸는지 사람이 읽을 수 있게 적는다. */
public class InvalidCommandException extends IllegalArgumentException {

    public InvalidCommandException(String message) {
        super(message);
    }
}
