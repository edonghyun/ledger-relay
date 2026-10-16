package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.UInt128;

/**
 * 이체 한 건. {@code transfer(500).from(payer).to(payee)}로 만들면 새 ID가 발급된다.
 * {@link #withAmount(long)}처럼 {@code with…}로 시작하는 말은 같은 ID에 그 필드만 바꾼 것이고,
 * {@link #withNewId()}는 같은 내용에 새 ID를 붙인 것이다.
 */
public record Transfer(byte[] id, Account from, Account to, long amount,
        int ledger, int code, long userData128, long userData64, int userData32) {

    public static Draft transfer(long amount) {
        return new Draft(amount);
    }

    public Transfer withAmount(long newAmount) {
        return new Transfer(id, from, to, newAmount, ledger, code, userData128, userData64, userData32);
    }

    public Transfer withDebitAccount(Account newFrom) {
        return new Transfer(id, newFrom, to, amount, ledger, code, userData128, userData64, userData32);
    }

    public Transfer withCreditAccount(Account newTo) {
        return new Transfer(id, from, newTo, amount, ledger, code, userData128, userData64, userData32);
    }

    public Transfer withLedger(int newLedger) {
        return new Transfer(id, from, to, amount, newLedger, code, userData128, userData64, userData32);
    }

    public Transfer withCode(int newCode) {
        return new Transfer(id, from, to, amount, ledger, newCode, userData128, userData64, userData32);
    }

    public Transfer withUserData128(long value) {
        return new Transfer(id, from, to, amount, ledger, code, value, userData64, userData32);
    }

    public Transfer withUserData64(long value) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, value, userData32);
    }

    public Transfer withUserData32(int value) {
        return new Transfer(id, from, to, amount, ledger, code, userData128, userData64, value);
    }

    public Transfer withNewId() {
        return new Transfer(UInt128.id(), from, to, amount, ledger, code, userData128, userData64, userData32);
    }

    @Override
    public String toString() {
        return amount + " (" + from + " → " + to + ")";
    }

    public record Draft(long amount) {
        public Routed from(Account from) {
            return new Routed(amount, from);
        }
    }

    public record Routed(long amount, Account from) {
        public Transfer to(Account to) {
            return new Transfer(UInt128.id(), from, to, amount,
                    TigerBeetleTestLedger.LEDGER, TigerBeetleTestLedger.CODE, 0, 0, 0);
        }
    }
}
