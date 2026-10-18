package io.github.ledgerrelay.tigerbeetle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TB 어댑터는 판단하지 않는다. 종류→flags, 결과 코드 이름만 옮긴다. 재시도·분류·전이는 core에 있다. */
@DisplayName("TB 어댑터의 경계 — 판단은 core에만")
class TigerBeetleArchitectureTest {

    static final String CORE = "io.github.ledgerrelay.core";

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ledgerrelay.tigerbeetle");

    /** testFixtures(Testcontainers를 쓰는 픽스처)는 이 패키지에만 있다. DoNotIncludeTests는 testFixtures를 거르지 못한다. */
    static final String FIXTURES = "io.github.ledgerrelay.tigerbeetle.testing..";

    @Test
    @DisplayName("어댑터가 쓰는 core는 공개 API와 포트뿐이다 — 도메인 규칙·서비스를 쓰지 않는다")
    void theAdapterMakesNoDecisions() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.tigerbeetle..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat(DescribedPredicate.describe("core 밖, core 공개 API, core.spi",
                        (JavaClass c) -> !inPackage(c, CORE) || c.getPackageName().equals(CORE)
                                || inPackage(c, CORE + ".spi")))
                .check(MAIN);
    }

    @Test
    @DisplayName("어댑터는 JDK와 TB Java 클라이언트, core만 쓴다")
    void theAdapterUsesOnlyTheTigerBeetleClientAndTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.tigerbeetle..").and().resideOutsideOfPackage(FIXTURES)
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.tigerbeetle..",
                        "io.github.ledgerrelay..")
                .check(MAIN);
    }

    /** 점 경계로 비교한다. {@code core.spike}가 {@code core.spi}로 읽히지 않게. */
    private static boolean inPackage(JavaClass c, String pkg) {
        return c.getPackageName().equals(pkg) || c.getPackageName().startsWith(pkg + ".");
    }
}
