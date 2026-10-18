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
 * 헥사고날 의존 방향. 안쪽은 바깥을 모른다.
 *
 * <pre>
 * 공개 API ◀── domain ◀── spi ◀── application
 *    ▲______________________________│
 * </pre>
 */
@DisplayName("헥사고날 경계 — 의존은 안쪽으로만 향한다")
class ArchitectureTest {

    /**
     * main 소스셋의 클래스만 본다. 테스트와 testFixtures는 경계 밖이다. testFixtures를 켜면 Gradle이 main을
     * 폴더 대신 jar로 테스트 클래스패스에 올리므로 둘 다 받는다.
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
    @DisplayName("공개 API는 도메인 규칙·서비스·포트를 모른다 — 앱 코드가 내부 타입에 묶이지 않게")
    void thePublicApiKnowsNothingInside() {
        // then
        noClasses().that().resideInAPackage(PUBLIC)
                .should().dependOnClassesThat().resideInAnyPackage(DOMAIN, APPLICATION, SPI)
                .check(MAIN);
    }

    @Test
    @DisplayName("도메인 규칙은 서비스와 포트를 모른다 — 규칙은 저장 방식과 상관없이 참이다")
    void theDomainKnowsNeitherServiceNorPorts() {
        // then
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(APPLICATION, SPI)
                .check(MAIN);
    }

    @Test
    @DisplayName("core는 JDK 말고 아무것도 쓰지 않는다 — Spring·PG·TB는 어댑터 모듈만 안다")
    void coreUsesOnlyTheJdk() {
        // then
        classes().that().resideInAPackage("io.github.ledgerrelay.core..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "io.github.ledgerrelay.core..")
                .check(MAIN);
    }

    @Test
    @DisplayName("포트는 서비스를 모른다 — 어댑터가 서비스 구현에 묶이지 않게")
    void portsDoNotKnowTheService() {
        // then
        noClasses().that().resideInAPackage(SPI)
                .should().dependOnClassesThat().resideInAPackage(APPLICATION)
                .check(MAIN);
    }

    @Test
    @DisplayName("핸들러를 부르는 클래스는 순서 키 포트를 모른다 — 키 행을 쥔 채 핸들러가 주문 행을 기다려 앱의 접수와 교착하지 않게")
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
