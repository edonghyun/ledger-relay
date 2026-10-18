package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.util.Objects;

/** 접수증(submit()). 같은 명령을 다시 접수하면 처음 접수증을 그대로 돌려받는다. */
public record CommandReceipt(String commandId, BigInteger transferId) {

    public CommandReceipt {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(transferId, "transferId");
    }
}
