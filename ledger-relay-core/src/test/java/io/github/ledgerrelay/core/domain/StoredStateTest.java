package io.github.ledgerrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.ResultCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("저장 상태 — 스펙 §4.1, §4.4")
class StoredStateTest {

    @Nested
    @Order(1)
    @DisplayName("TB 결과 분류를 받은 처리 중 명령은")
    class AfterATigerBeetleResult {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                APPLIED,       APPLIED
                CONFLICT,      CONFLICT
                BUSINESS,      REJECTED
                PRIOR_FAILURE, REJECTED
                INVALID,       REJECTED
                UNSUPPORTED,   CONFLICT
                """)
        @DisplayName("분류가 정한 최종 상태로 끝난다")
        void endsInTheStateItsCategoryNames(ResultCategory category, StoredState expected) {
            assertThat(StoredState.finalStateFor(category)).as("%s의 최종 상태", category).isEqualTo(expected);
        }
    }
}
