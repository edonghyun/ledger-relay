package io.github.ledgerrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.ResultCategory;
import io.github.ledgerrelay.core.domain.Transition.Effect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("State transitions — the transition table, G5 and G6")
class TransitionTableTest {

    /**
     * The transition table with one line per cell. The expected values are written like this.
     * state name (+token +handler +backoff +keepResult +recordSubmission +countFailure) = moves to that state
     * with those effects.
     * unchanged = the gate is closed and nothing happens. 0 rows = the fenced UPDATE updates 0 rows.
     * impossible (module bug) = the module creating this event is a bug.
     * n/a (event cannot occur) = in this state the event cannot occur by definition.
     * Both notations explain why something cannot happen. Reaching an impossible cell makes the state machine refuse it
     * as a bug, and a n/a cell cannot be reached by the state machine's inputs (CommandStateMachineTest › Compared with
     * the transition table).
     */
    static final String TRANSITION_TABLE = """
            # PENDING
            PENDING, CLAIM_WITH_GATE_OPEN,               CLAIMED+token
            PENDING, CLAIM_WITH_GATE_CLOSED,             unchanged
            PENDING, CLAIM_WITH_GATE_HALTED,             HALTED+token
            PENDING, SUBMITTING,                         impossible (module bug)
            PENDING, RESERVATION_NOT_APPLIED,            impossible (module bug)
            PENDING, TB_APPLIED,                         impossible (module bug)
            PENDING, TB_REJECTED,                        impossible (module bug)
            PENDING, TB_CONFLICT,                        impossible (module bug)
            PENDING, NO_REPLY_OR_ERROR,                  impossible (module bug)
            PENDING, HANDLER_FAILED,                     impossible (module bug)
            PENDING, LEASE_EXPIRED,                      n/a (event cannot occur)
            PENDING, STALE_TOKEN_WRITE,                  0 rows
            PENDING, ABANDONED_BY_OPERATOR,              impossible (module bug)
            # CLAIMED: claim is impossible before the lease expires. Skipping happens after the claim
            CLAIMED, CLAIM_WITH_GATE_OPEN,               impossible (module bug)
            CLAIMED, CLAIM_WITH_GATE_CLOSED,             impossible (module bug)
            CLAIMED, CLAIM_WITH_GATE_HALTED,             impossible (module bug)
            CLAIMED, SUBMITTING,                         CLAIMED+recordSubmission
            CLAIMED, RESERVATION_NOT_APPLIED,            SKIPPED+handler
            CLAIMED, TB_APPLIED,                         APPLIED+handler
            CLAIMED, TB_REJECTED,                        REJECTED+handler
            CLAIMED, TB_CONFLICT,                        CONFLICT+handler
            CLAIMED, NO_REPLY_OR_ERROR,                  PENDING+backoff
            CLAIMED, HANDLER_FAILED,                     PENDING+backoff+keepResult+countFailure
            CLAIMED, LEASE_EXPIRED,                      CLAIMED+token
            CLAIMED, STALE_TOKEN_WRITE,                  0 rows
            CLAIMED, ABANDONED_BY_OPERATOR,              impossible (module bug)
            # The 4 final states have the same lines
            APPLIED, CLAIM_WITH_GATE_OPEN,               impossible (module bug)
            APPLIED, CLAIM_WITH_GATE_CLOSED,             impossible (module bug)
            APPLIED, CLAIM_WITH_GATE_HALTED,             impossible (module bug)
            APPLIED, SUBMITTING,                         0 rows
            APPLIED, RESERVATION_NOT_APPLIED,            0 rows
            APPLIED, TB_APPLIED,                         0 rows
            APPLIED, TB_REJECTED,                        0 rows
            APPLIED, TB_CONFLICT,                        0 rows
            APPLIED, NO_REPLY_OR_ERROR,                  0 rows
            APPLIED, HANDLER_FAILED,                     n/a (event cannot occur)
            APPLIED, LEASE_EXPIRED,                      n/a (event cannot occur)
            APPLIED, STALE_TOKEN_WRITE,                  0 rows
            APPLIED, ABANDONED_BY_OPERATOR,              impossible (module bug)
            REJECTED, CLAIM_WITH_GATE_OPEN,               impossible (module bug)
            REJECTED, CLAIM_WITH_GATE_CLOSED,             impossible (module bug)
            REJECTED, CLAIM_WITH_GATE_HALTED,            impossible (module bug)
            REJECTED, SUBMITTING,                         0 rows
            REJECTED, RESERVATION_NOT_APPLIED,            0 rows
            REJECTED, TB_APPLIED,                         0 rows
            REJECTED, TB_REJECTED,                        0 rows
            REJECTED, TB_CONFLICT,                        0 rows
            REJECTED, NO_REPLY_OR_ERROR,                  0 rows
            REJECTED, HANDLER_FAILED,                     n/a (event cannot occur)
            REJECTED, LEASE_EXPIRED,                      n/a (event cannot occur)
            REJECTED, STALE_TOKEN_WRITE,                  0 rows
            REJECTED, ABANDONED_BY_OPERATOR,             impossible (module bug)
            CONFLICT, CLAIM_WITH_GATE_OPEN,               impossible (module bug)
            CONFLICT, CLAIM_WITH_GATE_CLOSED,             impossible (module bug)
            CONFLICT, CLAIM_WITH_GATE_HALTED,            impossible (module bug)
            CONFLICT, SUBMITTING,                         0 rows
            CONFLICT, RESERVATION_NOT_APPLIED,            0 rows
            CONFLICT, TB_APPLIED,                         0 rows
            CONFLICT, TB_REJECTED,                        0 rows
            CONFLICT, TB_CONFLICT,                        0 rows
            CONFLICT, NO_REPLY_OR_ERROR,                  0 rows
            CONFLICT, HANDLER_FAILED,                     n/a (event cannot occur)
            CONFLICT, LEASE_EXPIRED,                      n/a (event cannot occur)
            CONFLICT, STALE_TOKEN_WRITE,                  0 rows
            CONFLICT, ABANDONED_BY_OPERATOR,             impossible (module bug)
            SKIPPED, CLAIM_WITH_GATE_OPEN,               impossible (module bug)
            SKIPPED, CLAIM_WITH_GATE_CLOSED,             impossible (module bug)
            SKIPPED, CLAIM_WITH_GATE_HALTED,             impossible (module bug)
            SKIPPED, SUBMITTING,                         0 rows
            SKIPPED, RESERVATION_NOT_APPLIED,            0 rows
            SKIPPED, TB_APPLIED,                         0 rows
            SKIPPED, TB_REJECTED,                        0 rows
            SKIPPED, TB_CONFLICT,                        0 rows
            SKIPPED, NO_REPLY_OR_ERROR,                  0 rows
            SKIPPED, HANDLER_FAILED,                     n/a (event cannot occur)
            SKIPPED, LEASE_EXPIRED,                      n/a (event cannot occur)
            SKIPPED, STALE_TOKEN_WRITE,                  0 rows
            SKIPPED, ABANDONED_BY_OPERATOR,              impossible (module bug)
            # HALTED: not a claim candidate. It accepts only an operator abandon (docs/failure-modes.md F9)
            HALTED, CLAIM_WITH_GATE_OPEN,                impossible (module bug)
            HALTED, CLAIM_WITH_GATE_CLOSED,              impossible (module bug)
            HALTED, CLAIM_WITH_GATE_HALTED,              impossible (module bug)
            HALTED, SUBMITTING,                          impossible (module bug)
            HALTED, RESERVATION_NOT_APPLIED,             impossible (module bug)
            HALTED, TB_APPLIED,                          impossible (module bug)
            HALTED, TB_REJECTED,                         impossible (module bug)
            HALTED, TB_CONFLICT,                         impossible (module bug)
            HALTED, NO_REPLY_OR_ERROR,                   impossible (module bug)
            HALTED, HANDLER_FAILED,                      n/a (event cannot occur)
            HALTED, LEASE_EXPIRED,                       n/a (event cannot occur)
            HALTED, STALE_TOKEN_WRITE,                   0 rows
            HALTED, ABANDONED_BY_OPERATOR,               SKIPPED+handler
            """;

    @Nested
    @Order(1)
    @DisplayName("For every cell of the transition table")
    class EveryCell {

        @ParameterizedTest(name = "{0} + {1} → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = TRANSITION_TABLE)
        @DisplayName("The result written in the table comes out")
        void producesTheTabledTransition(StoredState current, CommandEvent event, String expected) {
            // when
            Transition next = TransitionTable.next(current, event);

            // then
            assertTransition(current, event, next, expected);
        }

        @Test
        @DisplayName("The transition table holds every combination of state and event exactly once — so no cell is empty")
        void tableCoversEveryPairOnce() {
            // given
            List<String> pairsInTheTable = rows().stream().map(row -> row[0] + "," + row[1]).toList();
            List<String> allPairs = new ArrayList<>();
            for (StoredState state : StoredState.values()) {
                for (CommandEvent event : CommandEvent.values()) {
                    allPairs.add(state + "," + event);
                }
            }

            // then
            assertThat(pairsInTheTable).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(allPairs);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("Looking at the whole table")
    class TableWideRules {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("A final state never changes to another state on any event — G5")
        void finalStatesNeverMove(StoredState finalState) {
            for (CommandEvent event : CommandEvent.values()) {
                // when
                Transition next = TransitionTable.next(finalState, event);

                // then
                assertThat(next.outcome()).as("state %s, event %s", finalState, event).isNotEqualTo(Transition.Outcome.MOVE);
            }
        }

        @Test
        @DisplayName("An accepted command moves only by a claim, and a claim always raises the lease token — so commands that are skipped or halted also end under fencing")
        void pendingCommandsMoveOnlyByAClaimThatIncrementsTheToken() {
            for (CommandEvent event : CommandEvent.values()) {
                // when
                Transition next = TransitionTable.next(StoredState.PENDING, event);
                if (next.outcome() != Transition.Outcome.MOVE) {
                    continue;
                }

                // then: only a command to be halted goes to halted (HALTED), the rest go to in progress (CLAIMED).
                // Either way the token goes up
                StoredState expectedTo = event == CommandEvent.CLAIM_WITH_GATE_HALTED ? StoredState.HALTED : StoredState.CLAIMED;
                assertThat(event).as("event that moved PENDING")
                        .isIn(CommandEvent.CLAIM_WITH_GATE_OPEN, CommandEvent.CLAIM_WITH_GATE_HALTED);
                assertThat(next)
                        .as("PENDING + %s: expected a move to %s that raises the token", event, expectedTo)
                        .isEqualTo(new Transition(Transition.Outcome.MOVE, expectedTo, Set.of(Effect.INCREMENT_LEASE_TOKEN)));
            }
        }

        @Test
        @DisplayName("The handler runs only on transitions into a final state, and on every one of them — G6")
        void handlerRunsExactlyOnEnteringAFinalState() {
            for (StoredState state : StoredState.values()) {
                for (CommandEvent event : CommandEvent.values()) {
                    // when
                    Transition next = TransitionTable.next(state, event);
                    if (next.outcome() != Transition.Outcome.MOVE) {
                        continue;
                    }

                    // then
                    if (next.to().isFinal()) {
                        assertThat(next.effects())
                                .as("%s + %s: enters final state %s but the handler run is missing", state, event, next.to())
                                .contains(Effect.RUN_HANDLER);
                    } else {
                        assertThat(next.effects())
                                .as("%s + %s: goes to non-final %s but has a handler run", state, event, next.to())
                                .doesNotContain(Effect.RUN_HANDLER);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ResultCategory.class)
        @DisplayName("The final state set by the classification table equals the state the transition table moves to — so the two tables do not drift apart when edited separately")
        void classificationAndTransitionAgreeOnTheFinalState(ResultCategory category) {
            // when
            Transition next = TransitionTable.next(StoredState.CLAIMED, CommandEvent.of(category));

            // then
            assertThat(next.to()).as("state in which an in-progress command ends after a %s result", category)
                    .isEqualTo(StoredState.finalStateFor(category));
        }
    }

    @Nested
    @Order(3)
    @DisplayName("A TB result category")
    class ResultEvents {

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                APPLIED,       TB_APPLIED
                CONFLICT,      TB_CONFLICT
                UNSUPPORTED,   TB_CONFLICT
                BUSINESS,      TB_REJECTED
                PRIOR_FAILURE, TB_REJECTED
                INVALID,       TB_REJECTED
                """)
        @DisplayName("Becomes one of the three TB events of the transition table (→)")
        void becomesATigerBeetleEvent(ResultCategory category, CommandEvent expected) {
            // when
            CommandEvent event = CommandEvent.of(category);

            // then
            assertThat(event).as("TB event of category %s", category).isEqualTo(expected);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    /** The transition follows the table's notation (the hand-written expected value). */
    private static void assertTransition(StoredState current, CommandEvent event, Transition actual, String notation) {
        assertThat(actual).as("in state %s, event %s is written in the table as '%s'", current, event, notation)
                .isEqualTo(parse(notation));
    }

    private static List<String[]> rows() {
        return TRANSITION_TABLE.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> Arrays.stream(line.split(",")).map(String::strip).toArray(String[]::new))
                .toList();
    }

    /**
     * Reads the table's expected-value notation into a {@link Transition}. It builds the values directly instead of
     * using the production factories ({@code Transition.zeroRows()} and so on). With the factories, a wrong factory
     * would make the expected value wrong too, and the table would check nothing.
     */
    private static Transition parse(String notation) {
        return switch (notation) {
            case "unchanged" -> new Transition(Transition.Outcome.UNCHANGED, null, Set.of());
            case "0 rows" -> new Transition(Transition.Outcome.ZERO_ROWS, null, Set.of());
            case "impossible (module bug)" -> new Transition(Transition.Outcome.IMPOSSIBLE, null, Set.of());
            case "n/a (event cannot occur)" -> new Transition(Transition.Outcome.NOT_APPLICABLE, null, Set.of());
            default -> {
                String[] parts = notation.split("\\+");
                Set<Effect> effects = EnumSet.noneOf(Effect.class);
                for (int i = 1; i < parts.length; i++) {
                    effects.add(switch (parts[i]) {
                        case "token" -> Effect.INCREMENT_LEASE_TOKEN;
                        case "handler" -> Effect.RUN_HANDLER;
                        case "backoff" -> Effect.BACKOFF;
                        case "keepResult" -> Effect.KEEP_KNOWN_RESULT;
                        case "recordSubmission" -> Effect.RECORD_SUBMISSION;
                        case "countFailure" -> Effect.COUNT_HANDLER_FAILURE;
                        default -> throw new IllegalArgumentException("notation error: " + parts[i]);
                    });
                }
                yield new Transition(Transition.Outcome.MOVE, StoredState.valueOf(parts[0]), effects);
            }
        };
    }
}
