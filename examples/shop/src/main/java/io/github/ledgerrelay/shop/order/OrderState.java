package io.github.ledgerrelay.shop.order;

/**
 * Order state. It follows authorize → capture in card payments. Paying only reserves (authorizes); the post (capture)
 * that moves the money to the merchant happens on shipping.
 *
 * <pre>
 * accepted → authorized → shipping → paid
 * accepted/authorized → cancelling → cancelled
 * failed (reservation rejected etc., with a reason)
 * </pre>
 */
public enum OrderState {
    ACCEPTED("accepted", "Accepted"),
    AUTHORIZED("authorized", "Authorized"),
    SHIP_REQUESTED("shipping", "Shipping"),
    PAID("paid", "Paid"),
    CANCEL_REQUESTED("cancelling", "Cancelling"),
    CANCELLED("cancelled", "Cancelled"),
    FAILED("failed", "Failed");

    private final String chip;
    private final String label;

    OrderState(String chip, String label) {
        this.chip = chip;
        this.label = label;
    }

    /** Short lowercase name used for the chip in the UI. */
    public String chip() {
        return chip;
    }

    /** Display name used in the order details. */
    public String label() {
        return label;
    }

    /** A state that never changes again. */
    public boolean isFinal() {
        return this == PAID || this == CANCELLED || this == FAILED;
    }
}
