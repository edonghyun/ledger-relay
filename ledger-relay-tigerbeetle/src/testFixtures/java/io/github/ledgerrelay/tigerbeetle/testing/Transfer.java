package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import java.math.BigInteger;

/**
 * 이체 한 건. {@code transfer(500).from(payer).to(payee)}로 만들면 새 ID가 발급된다.
 * {@code reservation(500).from(payer).to(payee)}는 금액을 보류만 하는 예약이고, {@link #postOf(Transfer)}·
 * {@link #voidOf(Transfer)}는 그 예약을 확정·해제하는 이체다.
 * {@link #withAmount(long)}처럼 {@code with…}로 시작하는 말은 같은 ID에 그 필드만 바꾼 것이고,
 * {@link #withNewId()}는 같은 내용에 새 ID를 붙인 것이다.
 *
 * @param pendingId 확정·해제가 가리키는 예약의 ID. 그 밖에는 null
 */
public record Transfer(byte[] id, Account from, Account to, BigInteger amount,
        int ledger, int code, long userData128, long userData64, int userData32,
        int flags, byte[] pendingId, long timeoutSeconds) {

    /** TB가 확정에서 "예약 금액 전부"로 읽는 값(2^128-1). */
    public static final BigInteger AMOUNT_MAX = TransferBatch.AMOUNT_MAX;

    public static Draft transfer(long amount) {
        return new Draft(amount, TransferFlags.NONE);
    }

    public static Draft reservation(long amount) {
        return new Draft(amount, TransferFlags.PENDING);
    }

    /** 예약을 금액 없이(전액) 확정한다. 계좌·ledger·code는 0으로 보내 예약의 값을 물려받는다. */
    public static Transfer postOf(Transfer hold) {
        return new Transfer(UInt128.id(), null, null, AMOUNT_MAX, 0, 0, 0, 0, 0,
                TransferFlags.POST_PENDING_TRANSFER, hold.id(), 0);
    }

    /** 예약을 해제한다. 금액·계좌·ledger·code는 0으로 보내 예약의 값을 물려받는다. */
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

    /** 예약의 만료(초). 예약이 아닌 이체에 주면 TB가 거부한다. */
    public Transfer withTimeout(long seconds) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, userData64, userData32,
                flags, pendingId, seconds);
    }

    /** 확정·해제가 가리키는 이체를 바꾼다. */
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
        String shown = amount.equals(AMOUNT_MAX) ? "전액" : amount.toString();
        if ((flags & TransferFlags.POST_PENDING_TRANSFER) != 0) {
            return "확정 " + shown;
        }
        if ((flags & TransferFlags.VOID_PENDING_TRANSFER) != 0) {
            return "해제 " + shown;
        }
        String kind = (flags & TransferFlags.PENDING) != 0 ? "예약 " : "";
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
