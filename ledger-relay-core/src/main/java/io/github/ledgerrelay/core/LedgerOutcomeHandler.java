package io.github.ledgerrelay.core;

/**
 * The outcome handler. The app registers one as a bean. It runs inside the transaction that moves the command to its
 * final state (G6). It does only fast local DB work and has no external side effects. If it throws, the transition is
 * undone and runs again later. In hexagonal terms it is an outgoing port that core calls and the app implements; it
 * lives in the public API because the app implements it.
 */
@FunctionalInterface
public interface LedgerOutcomeHandler {
    void onCompleted(CommandStatus outcome);
}
