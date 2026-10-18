package io.github.ledgerrelay.core.spi;

/**
 * The store that locks ordering key rows. A lock lasts until the end of the transaction. This port is the only one
 * that locks key rows; {@link CommandStore} does not. A transaction that runs the handler does not use this port: the
 * app accepts while holding its order rows and may wait for a key row, so if the handler waited for an order row while
 * a key row was held, they would deadlock.
 */
public interface OrderingKeys {

    /**
     * Locks the ordering key until the end of the transaction and issues the next seq of that key. Each key counts up
     * from 1, and gaps can occur.
     */
    long lockKeyAndTakeSeq(String orderingKey);

    /**
     * Moves a head held by a finished command to the next unfinished command of that key. If there is none, the head
     * is cleared. It advances at most limit keys, and passes over key rows held by another transaction without waiting
     * (the next call advances them). The claim calls it before {@link CommandStore#lockDue}. A store that computes the
     * head every time has nothing to do here.
     */
    void passOnFinishedHeads(int limit);
}
