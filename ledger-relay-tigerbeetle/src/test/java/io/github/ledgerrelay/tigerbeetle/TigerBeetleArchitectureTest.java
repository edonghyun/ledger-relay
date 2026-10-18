package io.github.ledgerrelay.tigerbeetle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The TB adapter makes no decisions. It maps kinds to flags and carries result code names. Retries, classification and
 * transitions live in core.
 */
@DisplayName("TB adapter boundary — decisions only in core")
class TigerBeetleArchitectureTest {

    static final String CORE = "io.github.ledgerrelay.core";

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ledgerrelay.tigerbeetle");

    /**
     * testFixtures (fixtures that use Testcontainers) live only in this package. DoNotIncludeTests does not filter
     * testFixtures.
     */
    static final String FIXTURES = "io.github.ledgerrelay.tigerbeetle.testing..";

    @Test
    @DisplayName("The adapter uses only core's public API and ports — no domain rules or services")
    void theAdapterMakesNoDecisions() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.tigerbeetle..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat(DescribedPredicate.describe("outside core, core public API, core.spi",
                        (JavaClass c) -> !inPackage(c, CORE) || c.getPackageName().equals(CORE)
                                || inPackage(c, CORE + ".spi")))
                .check(MAIN);
    }

    @Test
    @DisplayName("The adapter uses only the JDK, the TB Java client and core")
    void theAdapterUsesOnlyTheTigerBeetleClientAndTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.tigerbeetle..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.tigerbeetle..",
                        "io.github.ledgerrelay..")
                .check(MAIN);
    }

    /** Compares on dot boundaries, so that {@code core.spike} is not read as {@code core.spi}. */
    private static boolean inPackage(JavaClass c, String pkg) {
        return c.getPackageName().equals(pkg) || c.getPackageName().startsWith(pkg + ".");
    }
}
