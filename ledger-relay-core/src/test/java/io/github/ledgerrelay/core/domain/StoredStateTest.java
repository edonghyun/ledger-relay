package io.github.ledgerrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.Rejection;
import io.github.ledgerrelay.core.ResultCategory;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("저장 상태")
class StoredStateTest {

    @Nested
    @Order(1)
    @DisplayName("처리 중 명령이 TB 결과를 받으면")
    class OnATigerBeetleResult {

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
            // when
            StoredState endsIn = StoredState.finalStateFor(category);

            // then
            assertThat(endsIn).as("%s의 최종 상태", category).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResultCategory.class, names = "APPLIED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("그 최종 상태의 조회 결과에 그 분류의 코드를 사유로 담을 수 있다 — 공개 CommandStatus의 판정과 어긋나지 않는다")
        void agreesWithTheReasonCheckOfTheQueryResult(ResultCategory category) {
            // given
            Instant at = Instant.parse("2026-10-16T00:00:00Z");
            LedgerResultCode code = Arrays.stream(LedgerResultCode.values())
                    .filter(candidate -> candidate.category() == category).findFirst().orElseThrow();
            CommandStatus.State endsIn = StoredState.finalStateFor(code.category()).queryState(1);

            // when
            ThrowingCallable makingTheQueryResult = () -> new CommandStatus("pay-1", endsIn,
                    Optional.of(Rejection.of(code)), Optional.empty(), BigInteger.ONE, 1, at, Optional.of(at));

            // then
            assertThatCode(makingTheQueryResult)
                    .as("%s(%s)로 끝난 %s 조회 결과", code, code.category(), endsIn)
                    .doesNotThrowAnyException();
        }
    }

    /** 조회 상태 표. submitCount는 그 행이 TB에 제출된 횟수다. */
    static final String QUERY_STATE_TABLE = """
            PENDING,  0, ACCEPTED
            PENDING,  1, IN_PROGRESS
            PENDING,  7, IN_PROGRESS
            CLAIMED,  0, ACCEPTED
            CLAIMED,  1, IN_PROGRESS
            APPLIED,  1, APPLIED
            REJECTED, 1, REJECTED
            CONFLICT, 1, CONFLICT
            SKIPPED,  0, SKIPPED
            HALTED,   0, HALTED
            """;

    @Nested
    @Order(2)
    @DisplayName("조회할 때")
    class WhenQueried {

        @ParameterizedTest(name = "{0}, 제출 {1}회 → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = QUERY_STATE_TABLE)
        @DisplayName("저장 상태와 제출 횟수로 조회 상태가 정해진다 — TB에 보낸 적이 있으면 미확정도 처리 중이다")
        void showsTheQueryState(StoredState stored, long submitCount, CommandStatus.State expected) {
            // when
            CommandStatus.State shown = stored.queryState(submitCount);

            // then
            assertThat(shown)
                    .as("%s 상태에서 %d회 제출한 명령의 조회 상태", stored, submitCount)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("조회 상태 표는 모든 저장 상태를 한 번 이상 담는다")
        void tableCoversEveryStoredState() {
            // when
            List<String> statesInTheTable = QUERY_STATE_TABLE.lines()
                    .map(String::strip).filter(line -> !line.isEmpty())
                    .map(line -> line.split(",")[0].strip()).distinct().toList();

            // then
            assertThat(statesInTheTable).containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(StoredState.values()).map(Enum::name).toList());
        }

        @Test
        @DisplayName("제출 횟수가 음수면 저장소 버그이므로 거부한다")
        void refusesANegativeSubmitCount() {
            // when
            ThrowingCallable querying = () -> StoredState.PENDING.queryState(-1);

            // then
            assertThatThrownBy(querying)
                    .as("저장소 버그로 거부되기를 기대했다")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("submitCount");
        }
    }
}
