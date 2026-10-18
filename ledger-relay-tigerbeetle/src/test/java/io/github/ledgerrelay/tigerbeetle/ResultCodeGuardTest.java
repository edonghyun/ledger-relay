package io.github.ledgerrelay.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.core.LedgerResultCode;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Unclassified-code guard — the TB client's result codes and the codes core classifies are exactly the same")
class ResultCodeGuardTest {

    @Test
    @DisplayName("Every result code in the TB client is classified by core — a TB upgrade that adds a code stops here")
    void everyTigerBeetleCodeIsClassified() {
        // when
        Set<String> unclassified = minus(namesOf(CreateTransferStatus.values()), namesOf(LedgerResultCode.values()));

        // then
        assertEveryTigerBeetleCodeClassified(unclassified);
    }

    @Test
    @DisplayName("Every code core classifies exists in the TB client — so a code removed from TB does not stay in the classification table")
    void everyClassifiedCodeExistsInTigerBeetle() {
        // when
        Set<String> stale = minus(namesOf(LedgerResultCode.values()), namesOf(CreateTransferStatus.values()));

        // then
        assertNoStaleCode(stale);
    }

    private static Set<String> namesOf(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> minus(Set<String> all, Set<String> known) {
        Set<String> rest = new TreeSet<>(all);
        rest.removeAll(known);
        return rest;
    }

    private static void assertEveryTigerBeetleCodeClassified(Set<String> unclassified) {
        assertThat(unclassified)
                .as("TB has result codes that core does not classify — add them to the classification table, "
                        + "LedgerResultCode and ResultClassificationTest.CLASSIFICATION_TABLE")
                .isEmpty();
    }

    private static void assertNoStaleCode(Set<String> stale) {
        assertThat(stale)
                .as("core still has result codes that TB removed — delete them from LedgerResultCode and the "
                        + "classification table")
                .isEmpty();
    }
}
