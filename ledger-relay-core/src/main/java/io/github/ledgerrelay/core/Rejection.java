package io.github.ledgerrelay.core;

import java.util.Objects;

/**
 * The reason for a command that ended as rejected or conflict ({@code rejection() = {category, tbCode}}). The code
 * decides the category.
 */
public record Rejection(ResultCategory category, LedgerResultCode tbCode) {

    public Rejection {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(tbCode, "tbCode");
        if (category == ResultCategory.APPLIED) {
            throw new IllegalArgumentException("An applied result (" + tbCode + ") is not a rejection reason");
        }
        if (tbCode.category() != category) {
            throw new IllegalArgumentException("The category of " + tbCode + " is " + tbCode.category() + ", not " + category);
        }
    }

    public static Rejection of(LedgerResultCode tbCode) {
        return new Rejection(tbCode.category(), tbCode);
    }
}
