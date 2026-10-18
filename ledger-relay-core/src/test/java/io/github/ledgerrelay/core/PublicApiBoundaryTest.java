package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Public API boundary — the package the app imports holds only types the app uses")
class PublicApiBoundaryTest {

    /**
     * Top-level public types of the public package. Adding or removing one changes the public API, so edit this by
     * hand.
     */
    static final List<String> PUBLIC_TYPES = List.of(
            "CommandConflictException",
            "CommandKind",
            "CommandReceipt",
            "CommandStatus",
            "InvalidCommandException",
            "LedgerCommand",
            "LedgerOutcomeHandler",
            "LedgerRelay",
            "LedgerResultCode",
            "Rejection",
            "ReservationNotFoundException",
            "ResultCategory",
            "SkipReason",
            "HaltReason",
            "LedgerRelayOperator",
            "HaltedCommand",
            "AbandonOutcome",
            "CommandNotFoundException",
            "CannotAbandonException",
            "ReconciliationReport");

    static final String PACKAGE = "io.github.ledgerrelay.core";

    @Test
    @DisplayName("The public package holds only the listed types — rules go in domain, services in application, ports in spi")
    void onlyTheListedTypesArePublic() {
        // then
        assertThat(publicTopLevelTypes())
                .as("top-level public types of public package %s", PACKAGE)
                .containsExactlyInAnyOrderElementsOf(PUBLIC_TYPES);
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    /**
     * Names of the top-level public types in the public package, among classes from the main source set ({@link
     * ArchitectureTest#MAIN}).
     */
    private static List<String> publicTopLevelTypes() {
        return ArchitectureTest.MAIN.stream()
                .filter(type -> type.getPackageName().equals(PACKAGE))
                .filter(type -> type.isTopLevelClass() && type.getModifiers().contains(JavaModifier.PUBLIC))
                .map(type -> type.getSimpleName())
                .filter(name -> !name.equals("package-info"))
                .sorted()
                .toList();
    }
}
