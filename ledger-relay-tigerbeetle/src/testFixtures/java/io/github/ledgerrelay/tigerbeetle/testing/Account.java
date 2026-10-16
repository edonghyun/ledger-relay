package io.github.ledgerrelay.tigerbeetle.testing;

/** 테스트용 계좌. 이름은 실패 메시지에서 "구매자", "지갑"처럼 읽히게 하려고 둔다. */
public record Account(String name, byte[] id) {

    @Override
    public String toString() {
        return name;
    }
}
