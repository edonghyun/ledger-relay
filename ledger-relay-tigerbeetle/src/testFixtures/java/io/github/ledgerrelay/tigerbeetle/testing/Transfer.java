package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import java.math.BigInteger;

/**
 * One transfer. {@code transfer(500).from(payer).to(payee)} issues a new id.
 * {@code reservation(500).from(payer).to(payee)} is a reservation that only holds the amount, and
 * {@link #postOf(Transfer)} and {@link #voidOf(Transfer)} are the transfers that post or void that reservation.
 * Methods starting with {@code with…}, such as {@link #withAmount(long)}, keep the same id and change only that field;
 * {@link #withNewId()} keeps the same content with a new id.
 *
 * @param pendingId id of the reservation that a post or void refers to; null otherwise
 */
public record Transfer(byte[] id, Account from, Account to, BigInteger amount,
        int ledger, int code, long userData128, long userData64, int userData32,
        int flags, byte[] pendingId, long timeoutSeconds) {

    /** The value (2^128-1) that TB reads in a post as "the full reserved amount". */
    public static final BigInteger AMOUNT_MAX = TransferBatch.AMOUNT_MAX;

    public static Draft transfer(long amount) {
        return new Draft(amount, TransferFlags.NONE);
    }

    public static Draft reservation(long amount) {
        return new Draft(amount, TransferFlags.PENDING);
    }

    /**
     * Posts the reservation without an amount (full amount). Accounts, ledger and code are sent as 0 so they inherit
     * the reservation's values.
     */
    public static Transfer postOf(Transfer hold) {
        return new Transfer(UInt128.id(), null, null, AMOUNT_MAX, 0, 0, 0, 0, 0,
                TransferFlags.POST_PENDING_TRANSFER, hold.id(), 0);
    }

    /**
     * Voids the reservation. Amount, accounts, ledger and code are sent as 0 so they inherit the reservation's values.
     */
    public static Transfer voidOf(Transfer hold) {
        return new Transfer(UInt128.id(), null, null, BigInteger.ZERO, 0, 0, 0, 0, 0,
                TransferFlags.VOID_PENDING_TRANSFER, hold.id(), 0);
    }

    public Transfer withAmount(long newAmount) {
        return withAmount(BigInteger.valueOf(newAmount));
    }

    public Transfer withAmount(BigInteger newAmount) {
        return new Transfer(id, from, to, newAmount, ledger, code, userData128, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withDebitAccount(Account newFrom) {
        return new Transfer(id, newFrom, to, amount, ledger, code, userData128, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withCreditAccount(Account newTo) {
        return new Transfer(id, from, newTo, amount, ledger, code, userData128, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withLedger(int newLedger) {
        return new Transfer(id, from, to, amount, newLedger, code, userData128, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withCode(int newCode) {
        return new Transfer(id, from, to, amount, ledger, newCode, userData128, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withUserData128(long value) {
        return new Transfer(id, from, to, amount, ledger, code, value, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withUserData64(long value) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, value, userData32,
                flags, pendingId, timeoutSeconds);
    }

    public Transfer withUserData32(int value) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, userData64, value,
                flags, pendingId, timeoutSeconds);
    }

    /** Expiry (seconds) of a reservation. TB rejects it on a transfer that is not a reservation. */
    public Transfer withTimeout(long seconds) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, userData64, userData32,
                flags, pendingId, seconds);
    }

    /** Changes the transfer that a post or void refers to. */
    public Transfer withPendingId(Transfer other) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, userData64, userData32,
                flags, other.id(), timeoutSeconds);
    }

    public Transfer withNewId() {
        return new Transfer(UInt128.id(), from, to, amount, ledger, code, userData128, userData64, userData32,
                flags, pendingId, timeoutSeconds);
    }

    @Override
    public String toString() {
        String shown = amount.equals(AMOUNT_MAX) ? "full amount" : amount.toString();
        if ((flags & TransferFlags.POST_PENDING_TRANSFER) != 0) {
            return "post " + shown;
        }
        if ((flags & TransferFlags.VOID_PENDING_TRANSFER) != 0) {
            return "void " + shown;
        }
        String kind = (flags & TransferFlags.PENDING) != 0 ? "reservation " : "";
        return kind + shown + " (" + from + " → " + to + ")";
    }

    public record Draft(long amount, int flags) {
        public Routed from(Account from) {
            return new Routed(amount, flags, from);
        }
    }

    public record Routed(long amount, int flags, Account from) {
        public Transfer to(Account to) {
            return new Transfer(UInt128.id(), from, to, BigInteger.valueOf(amount),
                    TigerBeetleTestLedger.LEDGER, TigerBeetleTestLedger.CODE, 0, 0, 0, flags, null, 0);
        }
    }
}
