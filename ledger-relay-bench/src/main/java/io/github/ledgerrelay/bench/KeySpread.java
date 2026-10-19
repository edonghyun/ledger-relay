package io.github.ledgerrelay.bench;

/** How the commands of a run spread over ordering keys. Commands on one key are processed one at a time (G4). */
public enum KeySpread {
    /** Every command on its own key: nothing waits for an earlier command. */
    SPREAD,
    /** 100 keys: like 100 busy customers. */
    HUNDRED,
    /** One key: every command waits for the one before it. Shows the cost of ordering. */
    ONE;

    String key(int index) {
        return switch (this) {
            case SPREAD -> "k" + index;
            case HUNDRED -> "k" + (index % 100);
            case ONE -> "k0";
        };
    }
}
