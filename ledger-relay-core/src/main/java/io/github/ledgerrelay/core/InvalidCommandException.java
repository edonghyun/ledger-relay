package io.github.ledgerrelay.core;

/** A command built with the builder broke a rule. The message tells a human reader what is wrong. */
public class InvalidCommandException extends IllegalArgumentException {

    public InvalidCommandException(String message) {
        super(message);
    }
}
