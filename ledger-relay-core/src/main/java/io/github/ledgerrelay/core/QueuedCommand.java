package io.github.ledgerrelay.core;

/** 게이트가 보는 같은 순서 키의 명령 하나. */
public record QueuedCommand(String commandId, long seq, CommandState state) {
}
