package io.github.ledgerrelay.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.CreateTransferStatus;
import io.github.ledgerrelay.core.LedgerResultCode;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("미분류 가드 — TB 클라이언트의 결과 코드와 core가 분류하는 코드는 정확히 같다")
class ResultCodeGuardTest {

    @Test
    @DisplayName("TB 클라이언트에 있는 결과 코드는 모두 core가 분류한다 — TB를 올려 새 코드가 생기면 여기서 멈춘다")
    void everyTigerBeetleCodeIsClassified() {
        // when
        Set<String> unclassified = minus(namesOf(CreateTransferStatus.values()), namesOf(LedgerResultCode.values()));

        // then
        assertEveryTigerBeetleCodeClassified(unclassified);
    }

    @Test
    @DisplayName("core가 분류하는 코드는 모두 TB 클라이언트에 있다 — TB에서 사라진 코드가 분류표에 남지 않게")
    void everyClassifiedCodeExistsInTigerBeetle() {
        // when
        Set<String> stale = minus(namesOf(LedgerResultCode.values()), namesOf(CreateTransferStatus.values()));

        // then
        assertNoStaleCode(stale);
    }

    private static Set<String> namesOf(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> minus(Set<String> all, Set<String> known) {
        Set<String> rest = new TreeSet<>(all);
        rest.removeAll(known);
        return rest;
    }

    private static void assertEveryTigerBeetleCodeClassified(Set<String> unclassified) {
        assertThat(unclassified)
                .as("TB에 core가 분류하지 않는 결과 코드가 있다 — 분류표, LedgerResultCode, "
                        + "ResultClassificationTest.CLASSIFICATION_TABLE에 추가할 것")
                .isEmpty();
    }

    private static void assertNoStaleCode(Set<String> stale) {
        assertThat(stale)
                .as("TB에서 사라진 결과 코드가 core에 남아 있다 — LedgerResultCode와 분류표에서 지울 것")
                .isEmpty();
    }
}
