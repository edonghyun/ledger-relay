package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공개 API의 경계 — 앱이 import하는 패키지에는 앱이 쓸 타입만 둔다 (스펙 §6, §7)")
class PublicApiBoundaryTest {

    /** 공개 패키지의 최상위 공개 타입. 늘리거나 줄이는 것은 공개 API를 바꾸는 일이므로 손으로 고친다. */
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
            "SkipReason");

    static final String PACKAGE = "io.github.ledgerrelay.core";

    @Test
    @DisplayName("공개 패키지에는 정해 둔 타입만 있다 — 규칙은 domain에, 서비스는 application에, 포트는 spi에 둔다")
    void onlyTheListedTypesArePublic() {
        // then
        assertThat(publicTopLevelTypes())
                .as("공개 패키지 %s의 최상위 공개 타입", PACKAGE)
                .containsExactlyInAnyOrderElementsOf(PUBLIC_TYPES);
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** main 소스셋에서 가져온 클래스({@link ArchitectureTest#MAIN}) 중 공개 패키지의 최상위 공개 타입 이름. */
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
