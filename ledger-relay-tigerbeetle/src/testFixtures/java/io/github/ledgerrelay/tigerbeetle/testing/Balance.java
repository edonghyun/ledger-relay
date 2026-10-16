package io.github.ledgerrelay.tigerbeetle.testing;

/** 계좌의 확정·보류 금액. */
public record Balance(long debitsPosted, long creditsPosted, long debitsPending, long creditsPending) {

    public boolean isUntouched() {
        return debitsPosted == 0 && creditsPosted == 0 && debitsPending == 0 && creditsPending == 0;
    }

    @Override
    public String toString() {
        return "출금 " + debitsPosted + " · 입금 " + creditsPosted
                + " · 보류 출금 " + debitsPending + " · 보류 입금 " + creditsPending;
    }
}
