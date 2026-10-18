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

@DisplayName("Stored state")
class StoredStateTest {

    @Nested
    @Order(1)
    @DisplayName("When an in-progress command receives a TB result")
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
        @DisplayName("It ends in the final state its category names")
        void endsInTheStateItsCategoryNames(ResultCategory category, StoredState expected) {
            // when
            StoredState endsIn = StoredState.finalStateFor(category);

            // then
            assertThat(endsIn).as("final state for %s", category).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResultCategory.class, names = "APPLIED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("A query result in that final state can carry a code of that category as its reason — it agrees with the check in the public CommandStatus")
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
                    .as("query result that ended with %s (%s) in state %s", code, code.category(), endsIn)
                    .doesNotThrowAnyException();
        }
    }

    /** Query state table. submitCount is how many times the row was submitted to TB. */
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
    @DisplayName("When queried")
    class WhenQueried {

        @ParameterizedTest(name = "{0}, {1} submissions → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = QUERY_STATE_TABLE)
        @DisplayName("The stored state and submit count decide the query state — once sent to TB, an unknown result also counts as in progress")
        void showsTheQueryState(StoredState stored, long submitCount, CommandStatus.State expected) {
            // when
            CommandStatus.State shown = stored.queryState(submitCount);

            // then
            assertThat(shown)
                    .as("query state of a command in state %s submitted %d times", stored, submitCount)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("The query state table lists every stored state at least once")
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
        @DisplayName("A negative submit count is a store bug, so it is refused")
        void refusesANegativeSubmitCount() {
            // when
            ThrowingCallable querying = () -> StoredState.PENDING.queryState(-1);

            // then
            assertThatThrownBy(querying)
                    .as("expected refusal as a store bug")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("submitCount");
        }
    }
}
