package io.github.ledgerrelay.tigerbeetle.testing;

import com.tigerbeetle.UInt128;

/** 테스트용 계좌. 이름은 실패 메시지에서 "구매자", "지갑"처럼 읽히게 하려고 둔다. */
public record Account(String name, byte[] id) {

    /** 아직 원장에 열지 않은 계좌. 나중에 {@link TigerBeetleTestLedger#open(Account)}로 같은 ID 그대로 열 수 있다. */
    public static Account notOpened(String name) {
        return new Account(name, UInt128.id());
    }

    @Override
    public String toString() {
        return name;
    }
}
