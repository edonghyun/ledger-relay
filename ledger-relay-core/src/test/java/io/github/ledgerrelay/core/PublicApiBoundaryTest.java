package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공개 API의 경계 — 앱이 import하는 패키지에는 앱이 쓸 타입만 둔다 (스펙 §6, §7)")
class PublicApiBoundaryTest {

    /** 공개 패키지의 최상위 공개 타입. 늘리거나 줄이는 것은 공개 API를 바꾸는 일이므로 손으로 고친다. */
    static final List<String> PUBLIC_TYPES = List.of(
            "CommandKind",
            "CommandStatus",
            "InvalidCommandException",
            "LedgerCommand",
            "LedgerResultCode",
            "Rejection",
            "ResultCategory");

    static final String PACKAGE = "io.github.ledgerrelay.core";

    @Test
    @DisplayName("공개 패키지에는 정해 둔 타입만 있다 — 규칙은 domain에, 서비스는 application에, 포트는 spi에 둔다")
    void onlyTheListedTypesArePublic() throws Exception {
        assertThat(publicTopLevelTypes().stream().map(Class::getSimpleName).toList())
                .as("공개 패키지 %s의 최상위 공개 타입", PACKAGE)
                .containsExactlyInAnyOrderElementsOf(PUBLIC_TYPES);
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static List<Class<?>> publicTopLevelTypes() throws URISyntaxException, IOException, ClassNotFoundException {
        Path classes = Path.of(LedgerCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.list(classes.resolve(PACKAGE.replace('.', '/')))) {
            for (String file : files.map(p -> p.getFileName().toString()).sorted().toList()) {
                if (!file.endsWith(".class") || file.contains("$") || file.equals("package-info.class")) {
                    continue;
                }
                Class<?> type = Class.forName(PACKAGE + "." + file.substring(0, file.length() - ".class".length()));
                if (Modifier.isPublic(type.getModifiers())) {
                    types.add(type);
                }
            }
        }
        return types;
    }
}
