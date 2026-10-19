package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shop reads the library through its public API, not its tables")
class LibraryTableAccessTest {

    static final Path SOURCES = Path.of("src/main/java/io/github/ledgerrelay/shop");

    @Test
    @DisplayName("Only the demo's fault injector touches ledger_command — it breaks the rules on purpose to stage scene 3")
    void onlyTheFaultInjectorReadsLibraryTables() throws IOException {
        // when
        List<String> touching = filesMentioning("ledger_command");

        // then
        assertThat(touching).as("source files that touch the library's command table")
                .containsExactly("demo/BadPostInjector.java");
    }

    private static List<String> filesMentioning(String table) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java")).filter(file -> mentions(file, table))
                    .map(file -> SOURCES.relativize(file).toString()).sorted().toList();
        }
    }

    private static boolean mentions(Path file, String table) {
        try {
            return Files.readString(file).contains(table);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
