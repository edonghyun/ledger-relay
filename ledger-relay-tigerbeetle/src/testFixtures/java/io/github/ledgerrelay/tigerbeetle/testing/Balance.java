package io.github.ledgerrelay.tigerbeetle.testing;

/** Posted and pending amounts of an account. */
public record Balance(long debitsPosted, long creditsPosted, long debitsPending, long creditsPending) {

    public boolean isUntouched() {
        return debitsPosted == 0 && creditsPosted == 0 && debitsPending == 0 && creditsPending == 0;
    }

    @Override
    public String toString() {
        return "debits " + debitsPosted + " · credits " + creditsPosted
                + " · pending debits " + debitsPending + " · pending credits " + creditsPending;
    }
}
