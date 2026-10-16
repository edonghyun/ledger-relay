package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.UInt128;

/**
 * 이체 한 건. {@code transfer(500).from(payer).to(payee)}로 만들면 새 ID가 발급된다.
 * {@link #withAmount(long)}은 같은 ID에 금액만 바꾼 것, {@link #withNewId()}는 같은 내용에 새 ID를 붙인 것이다.
 */
public record Transfer(byte[] id, Account from, Account to, long amount) {

    public static Draft transfer(long amount) {
        return new Draft(amount);
    }

    public Transfer withAmount(long newAmount) {
        return new Transfer(id, from, to, newAmount);
    }

    public Transfer withNewId() {
        return new Transfer(UInt128.id(), from, to, amount);
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
            return new Transfer(UInt128.id(), from, to, amount);
        }
    }
}
