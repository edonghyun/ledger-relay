package io.github.ledgerrelay.core;

/**
 * The category of a TB result code. The category decides whether to alert. StoredState.finalStateFor in domain decides
 * the final stored state the category leads to.
 */
public enum ResultCategory {
    /** created, exists */
    APPLIED(false),
    /** exists_with_different_*: different content under the same id. A sign of a bug (G7). */
    CONFLICT(true),
    /** Business rejection: balance, limit, expiry, already posted or voided, and so on. */
    BUSINESS(false),
    /**
     * id_already_failed: an earlier attempt whose response was lost ended with one of TB's 7 transient failures
     * (balance or limit: 2, account closed: 2, account or reservation not found: 3). Which one is unknown, and it may
     * also have been an invalid request (INVALID), so it alerts.
     */
    PRIOR_FAILURE(true),
    /** Invalid request: an app bug or a configuration error. */
    INVALID(true),
    /** Codes of features v1 does not use (linked, imported). Handled as CONFLICT. */
    UNSUPPORTED(true);

    private final boolean alerts;

    ResultCategory(boolean alerts) {
        this.alerts = alerts;
    }

    public boolean alerts() {
        return alerts;
    }
}
