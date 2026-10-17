package io.github.ledgerrelay.jdbc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 저장소 어댑터는 판단하지 않는다(02a P2a-16). 판단은 core의 서비스와 도메인 규칙에만 있다. */
@DisplayName("jdbc 어댑터의 경계 — 판단은 core에만 (스펙 §7)")
class JdbcArchitectureTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ledgerrelay.jdbc");

    @Test
    @DisplayName("어댑터는 상태 기계·게이트·전이표·해시·서비스를 쓰지 않는다 — SQL에 업무 규칙이 새지 않게")
    void theAdapterMakesNoDecisions() {
        // then
        noClasses().that().resideInAPackage("io.github.ledgerrelay.jdbc..")
                .should().dependOnClassesThat().resideInAPackage("io.github.ledgerrelay.core.application..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("io.github.ledgerrelay.core.domain.CommandStateMachine")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("io.github.ledgerrelay.core.domain.OrderingGate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("io.github.ledgerrelay.core.domain.TransitionTable")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("io.github.ledgerrelay.core.domain.ContentHash")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("io.github.ledgerrelay.core.domain.Step")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("io.github.ledgerrelay.core.domain.StepResult")
                .check(MAIN);
    }

    @Test
    @DisplayName("어댑터는 JDK와 Spring JDBC·TX, core만 쓴다 — 드라이버·풀·JPA는 앱이 고른다")
    void theAdapterUsesOnlySpringJdbcAndTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.jdbc..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax.sql..",
                        "org.springframework.jdbc..", "org.springframework.transaction..", "org.springframework.dao..",
                        "io.github.ledgerrelay..")
                .check(MAIN);
    }
}
