package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.UInt128;

/** Test account. The name exists so that failure messages read like "buyer" or "wallet". */
public record Account(String name, byte[] id) {

    /**
     * An account not yet opened in the ledger. It can be opened later with the same id via
     * {@link TigerBeetleTestLedger#open(Account)}.
     */
    public static Account notOpened(String name) {
        return new Account(name, UInt128.id());
    }

    @Override
    public String toString() {
        return name;
    }
}
