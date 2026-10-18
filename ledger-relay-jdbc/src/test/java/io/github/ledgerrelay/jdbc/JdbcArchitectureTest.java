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

/** 저장소 어댑터는 판단하지 않는다. 판단은 core의 서비스와 도메인 규칙에만 있다. */
@DisplayName("jdbc 어댑터의 경계 — 판단은 core에만")
class JdbcArchitectureTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ledgerrelay.jdbc");

    /** testFixtures(Testcontainers를 쓰는 픽스처)는 이 패키지에만 있다. DoNotIncludeTests는 testFixtures를 거르지 못한다. */
    static final String FIXTURES = "io.github.ledgerrelay.jdbc.testing..";

    /** jdbc가 읽고 쓰는 도메인 값. 판단(상태 기계, 게이트, 전이표, 해시, 서비스)은 여기에 없다. */
    static final Set<String> DOMAIN_VALUES = Set.of(
            "io.github.ledgerrelay.core.domain.CommandRow",
            "io.github.ledgerrelay.core.domain.QueuedCommand",
            "io.github.ledgerrelay.core.domain.StoredState");

    @Test
    @DisplayName("어댑터가 쓰는 core는 공개 API·포트·도메인 값뿐이다 — 판단 클래스가 새로 생겨도 SQL에 새지 않게")
    void theAdapterMakesNoDecisions() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.jdbc..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat(DescribedPredicate.describe(
                        "core 밖, core 공개 API, core.spi, 도메인 값(" + DOMAIN_VALUES + ")",
                        (JavaClass c) -> !c.getPackageName().startsWith("io.github.ledgerrelay.core")
                                || c.getPackageName().equals("io.github.ledgerrelay.core")
                                || c.getPackageName().startsWith("io.github.ledgerrelay.core.spi")
                                || DOMAIN_VALUES.contains(c.getName())))
                .check(MAIN);
    }

    @Test
    @DisplayName("어댑터는 JDK와 Spring JDBC·TX, core만 쓴다 — 드라이버·풀·JPA는 앱이 고른다")
    void theAdapterUsesOnlySpringJdbcAndTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.jdbc..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax.sql..",
                        "org.springframework.jdbc..", "org.springframework.transaction..", "org.springframework.dao..",
                        "io.github.ledgerrelay..")
                .check(MAIN);
    }
}
