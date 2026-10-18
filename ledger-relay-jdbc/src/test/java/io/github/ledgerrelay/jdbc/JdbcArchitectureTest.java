package io.github.ledgerrelay.jdbc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The storage adapter makes no decisions. Decisions live only in the core services and domain rules. */
@DisplayName("Boundaries of the jdbc adapter — decisions only in core")
class JdbcArchitectureTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ledgerrelay.jdbc");

    /** testFixtures (fixtures that use Testcontainers) live only in this package. DoNotIncludeTests does not filter testFixtures. */
    static final String FIXTURES = "io.github.ledgerrelay.jdbc.testing..";

    /** Domain values that jdbc reads and writes. Decisions (state machine, gates, transition table, hash, services) are not here. */
    static final Set<String> DOMAIN_VALUES = Set.of(
            "io.github.ledgerrelay.core.domain.CommandRow",
            "io.github.ledgerrelay.core.domain.QueuedCommand",
            "io.github.ledgerrelay.core.domain.StoredState");

    @Test
    @DisplayName("The adapter uses only the public API, ports and domain values of core — so new decision classes cannot leak into SQL")
    void theAdapterMakesNoDecisions() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.jdbc..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat(DescribedPredicate.describe(
                        "outside core, core public API, core.spi, domain values (" + DOMAIN_VALUES + ")",
                        (JavaClass c) -> !c.getPackageName().startsWith("io.github.ledgerrelay.core")
                                || c.getPackageName().equals("io.github.ledgerrelay.core")
                                || c.getPackageName().startsWith("io.github.ledgerrelay.core.spi")
                                || DOMAIN_VALUES.contains(c.getName())))
                .check(MAIN);
    }

    @Test
    @DisplayName("The adapter uses only the JDK, Spring JDBC/TX and core — the app chooses the driver, the pool and JPA")
    void theAdapterUsesOnlySpringJdbcAndTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.jdbc..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax.sql..",
                        "org.springframework.jdbc..", "org.springframework.transaction..", "org.springframework.dao..",
                        "io.github.ledgerrelay..")
                .check(MAIN);
    }
}
