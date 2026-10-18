package io.github.ledgerrelay.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hexagonal dependency direction. The inside does not know the outside.
 *
 * <pre>
 * public API ◀── domain ◀── spi ◀── application
 *    ▲______________________________│
 * </pre>
 */
@DisplayName("Hexagonal boundaries — dependencies point inward only")
class ArchitectureTest {

    /**
     * Looks only at classes of the main source set. Tests and testFixtures are outside the boundary. With testFixtures
     * enabled, Gradle puts main on the test classpath as a jar instead of a folder, so both are accepted.
     */
    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(location -> location.contains("/classes/java/main/")
                    || (location.contains("/build/libs/ledger-relay-core-") && !location.contains("test-fixtures")))
            .importPackages("io.github.ledgerrelay.core");

    static final String PUBLIC = "io.github.ledgerrelay.core";
    static final String DOMAIN = "io.github.ledgerrelay.core.domain..";
    static final String APPLICATION = "io.github.ledgerrelay.core.application..";
    static final String SPI = "io.github.ledgerrelay.core.spi..";

    @Test
    @DisplayName("The public API knows nothing of domain rules, services or ports — so app code is not tied to internal types")
    void thePublicApiKnowsNothingInside() {
        // then
        noClasses().that().resideInAPackage(PUBLIC)
                .should().dependOnClassesThat().resideInAnyPackage(DOMAIN, APPLICATION, SPI)
                .check(MAIN);
    }

    @Test
    @DisplayName("Domain rules know neither services nor ports — the rules hold regardless of how things are stored")
    void theDomainKnowsNeitherServiceNorPorts() {
        // then
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(APPLICATION, SPI)
                .check(MAIN);
    }

    @Test
    @DisplayName("core uses nothing but the JDK — only adapter modules know Spring, PG and TB")
    void coreUsesOnlyTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.core..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "io.github.ledgerrelay.core..")
                .check(MAIN);
    }

    @Test
    @DisplayName("Ports do not know the service — so adapters are not tied to the service implementation")
    void portsDoNotKnowTheService() {
        // then
        noClasses().that().resideInAPackage(SPI)
                .should().dependOnClassesThat().resideInAPackage(APPLICATION)
                .check(MAIN);
    }

    @Test
    @DisplayName("Classes that call the handler do not know the ordering key port — so a handler waiting on an order row while holding a key row cannot deadlock with the app's submit")
    void theHandlerPathCannotLockAKey() {
        // then
        noClasses().that(callTheOutcomeHandler())
                .should().dependOnClassesThat().areAssignableTo(OrderingKeys.class)
                .check(MAIN);
    }

    private static DescribedPredicate<JavaClass> callTheOutcomeHandler() {
        return DescribedPredicate.describe("call LedgerOutcomeHandler.onCompleted", javaClass ->
                javaClass.getMethodCallsFromSelf().stream().anyMatch(call -> call.getName().equals("onCompleted")
                        && call.getTargetOwner().isAssignableTo(LedgerOutcomeHandler.class)));
    }
}
