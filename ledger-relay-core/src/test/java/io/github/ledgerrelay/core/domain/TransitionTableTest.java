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

@DisplayName("상태 전이 — 스펙 §4.3 전이표, G5·G6")
class TransitionTableTest {

    /**
     * 스펙 §4.3 전이표를 칸 하나에 한 줄로 옮긴 것. 기대값 표기는 이렇다.
     * 상태 이름(+토큰 +핸들러 +backoff +결과보존 +제출기록 +실패집계) = 그 상태로 이동하며 해당 효과를 낸다.
     * 그대로 = 게이트가 닫혀 아무 일도 없다. 0행 = fenced UPDATE가 0행을 갱신한다.
     * 불가(모듈 버그) = 모듈이 이 사건을 만들면 버그다. 해당없음(생길 수 없는 사건) = 이 상태에서는 개념상 생길 수 없다.
     * 두 표기는 왜 일어날 수 없는지의 설명이고 처리는 같다. 상태 기계는 둘 다 버그로 거부한다(CommandStateMachineTest).
     */
    static final String TRANSITION_TABLE = """
            # PENDING
            PENDING, CLAIM_WITH_GATE_OPEN,               CLAIMED+토큰
            PENDING, CLAIM_WITH_GATE_CLOSED,             그대로
            PENDING, CLAIM_WITH_GATE_HALTED,             HALTED+토큰
            PENDING, SUBMITTING,                         불가(모듈 버그)
            PENDING, RESERVATION_NOT_APPLIED,            불가(모듈 버그)
            PENDING, TB_APPLIED,                         불가(모듈 버그)
            PENDING, TB_REJECTED,                        불가(모듈 버그)
            PENDING, TB_CONFLICT,                        불가(모듈 버그)
            PENDING, NO_REPLY_OR_ERROR,                  불가(모듈 버그)
            PENDING, HANDLER_FAILED,                     불가(모듈 버그)
            PENDING, LEASE_EXPIRED,                      해당없음(생길 수 없는 사건)
            PENDING, STALE_TOKEN_WRITE,                  0행
            PENDING, ABANDONED_BY_OPERATOR,              불가(모듈 버그)
            # CLAIMED: claim은 lease가 만료되기 전이면 불가. 건너뜀은 claim한 뒤에 일어난다
            CLAIMED, CLAIM_WITH_GATE_OPEN,               불가(모듈 버그)
            CLAIMED, CLAIM_WITH_GATE_CLOSED,             불가(모듈 버그)
            CLAIMED, CLAIM_WITH_GATE_HALTED,             불가(모듈 버그)
            CLAIMED, SUBMITTING,                         CLAIMED+제출기록
            CLAIMED, RESERVATION_NOT_APPLIED,            SKIPPED+핸들러
            CLAIMED, TB_APPLIED,                         APPLIED+핸들러
            CLAIMED, TB_REJECTED,                        REJECTED+핸들러
            CLAIMED, TB_CONFLICT,                        CONFLICT+핸들러
            CLAIMED, NO_REPLY_OR_ERROR,                  PENDING+backoff
            CLAIMED, HANDLER_FAILED,                     PENDING+backoff+결과보존+실패집계
            CLAIMED, LEASE_EXPIRED,                      CLAIMED+토큰
            CLAIMED, STALE_TOKEN_WRITE,                  0행
            CLAIMED, ABANDONED_BY_OPERATOR,              불가(모듈 버그)
            # 최종 상태 4개는 같은 줄을 갖는다
            APPLIED, CLAIM_WITH_GATE_OPEN,               불가(모듈 버그)
            APPLIED, CLAIM_WITH_GATE_CLOSED,             불가(모듈 버그)
            APPLIED, CLAIM_WITH_GATE_HALTED,             불가(모듈 버그)
            APPLIED, SUBMITTING,                         0행
            APPLIED, RESERVATION_NOT_APPLIED,            0행
            APPLIED, TB_APPLIED,                         0행
            APPLIED, TB_REJECTED,                        0행
            APPLIED, TB_CONFLICT,                        0행
            APPLIED, NO_REPLY_OR_ERROR,                  0행
            APPLIED, HANDLER_FAILED,                     해당없음(생길 수 없는 사건)
            APPLIED, LEASE_EXPIRED,                      해당없음(생길 수 없는 사건)
            APPLIED, STALE_TOKEN_WRITE,                  0행
            APPLIED, ABANDONED_BY_OPERATOR,              불가(모듈 버그)
            REJECTED, CLAIM_WITH_GATE_OPEN,               불가(모듈 버그)
            REJECTED, CLAIM_WITH_GATE_CLOSED,             불가(모듈 버그)
            REJECTED, CLAIM_WITH_GATE_HALTED,            불가(모듈 버그)
            REJECTED, SUBMITTING,                         0행
            REJECTED, RESERVATION_NOT_APPLIED,            0행
            REJECTED, TB_APPLIED,                         0행
            REJECTED, TB_REJECTED,                        0행
            REJECTED, TB_CONFLICT,                        0행
            REJECTED, NO_REPLY_OR_ERROR,                  0행
            REJECTED, HANDLER_FAILED,                     해당없음(생길 수 없는 사건)
            REJECTED, LEASE_EXPIRED,                      해당없음(생길 수 없는 사건)
            REJECTED, STALE_TOKEN_WRITE,                  0행
            REJECTED, ABANDONED_BY_OPERATOR,             불가(모듈 버그)
            CONFLICT, CLAIM_WITH_GATE_OPEN,               불가(모듈 버그)
            CONFLICT, CLAIM_WITH_GATE_CLOSED,             불가(모듈 버그)
            CONFLICT, CLAIM_WITH_GATE_HALTED,            불가(모듈 버그)
            CONFLICT, SUBMITTING,                         0행
            CONFLICT, RESERVATION_NOT_APPLIED,            0행
            CONFLICT, TB_APPLIED,                         0행
            CONFLICT, TB_REJECTED,                        0행
            CONFLICT, TB_CONFLICT,                        0행
            CONFLICT, NO_REPLY_OR_ERROR,                  0행
            CONFLICT, HANDLER_FAILED,                     해당없음(생길 수 없는 사건)
            CONFLICT, LEASE_EXPIRED,                      해당없음(생길 수 없는 사건)
            CONFLICT, STALE_TOKEN_WRITE,                  0행
            CONFLICT, ABANDONED_BY_OPERATOR,             불가(모듈 버그)
            SKIPPED, CLAIM_WITH_GATE_OPEN,               불가(모듈 버그)
            SKIPPED, CLAIM_WITH_GATE_CLOSED,             불가(모듈 버그)
            SKIPPED, CLAIM_WITH_GATE_HALTED,             불가(모듈 버그)
            SKIPPED, SUBMITTING,                         0행
            SKIPPED, RESERVATION_NOT_APPLIED,            0행
            SKIPPED, TB_APPLIED,                         0행
            SKIPPED, TB_REJECTED,                        0행
            SKIPPED, TB_CONFLICT,                        0행
            SKIPPED, NO_REPLY_OR_ERROR,                  0행
            SKIPPED, HANDLER_FAILED,                     해당없음(생길 수 없는 사건)
            SKIPPED, LEASE_EXPIRED,                      해당없음(생길 수 없는 사건)
            SKIPPED, STALE_TOKEN_WRITE,                  0행
            SKIPPED, ABANDONED_BY_OPERATOR,              불가(모듈 버그)
            # HALTED: claim 후보가 아니다. 운영자의 포기만 받는다(§5.2 HALT, docs/failure-modes.md F9)
            HALTED, CLAIM_WITH_GATE_OPEN,                불가(모듈 버그)
            HALTED, CLAIM_WITH_GATE_CLOSED,              불가(모듈 버그)
            HALTED, CLAIM_WITH_GATE_HALTED,              불가(모듈 버그)
            HALTED, SUBMITTING,                          불가(모듈 버그)
            HALTED, RESERVATION_NOT_APPLIED,             불가(모듈 버그)
            HALTED, TB_APPLIED,                          불가(모듈 버그)
            HALTED, TB_REJECTED,                         불가(모듈 버그)
            HALTED, TB_CONFLICT,                         불가(모듈 버그)
            HALTED, NO_REPLY_OR_ERROR,                   불가(모듈 버그)
            HALTED, HANDLER_FAILED,                      해당없음(생길 수 없는 사건)
            HALTED, LEASE_EXPIRED,                       해당없음(생길 수 없는 사건)
            HALTED, STALE_TOKEN_WRITE,                   0행
            HALTED, ABANDONED_BY_OPERATOR,               SKIPPED+핸들러
            """;

    @Nested
    @Order(1)
    @DisplayName("전이표의 칸마다")
    class EveryCell {

        @ParameterizedTest(name = "{0} + {1} → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = TRANSITION_TABLE)
        @DisplayName("표에 적힌 결과가 나온다")
        void producesTheTabledTransition(StoredState current, CommandEvent event, String expected) {
            // when
            Transition next = TransitionTable.next(current, event);

            // then
            assertTransition(current, event, next, expected);
        }

        @Test
        @DisplayName("전이표는 모든 상태와 사건의 조합을 정확히 한 번씩 담는다 — 빈 칸이 없게")
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
    @DisplayName("표 전체를 놓고 보면")
    class TableWideRules {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoredState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("최종 상태는 어떤 사건에도 다른 상태로 바뀌지 않는다 — G5")
        void finalStatesNeverMove(StoredState finalState) {
            for (CommandEvent event : CommandEvent.values()) {
                // when
                Transition next = TransitionTable.next(finalState, event);

                // then
                assertThat(next.outcome()).as("%s 상태에서 %s 사건", finalState, event).isNotEqualTo(Transition.Outcome.MOVE);
            }
        }

        @Test
        @DisplayName("접수된 명령은 claim으로만 움직이고, claim은 늘 lease 토큰을 올린다 — 건너뛰거나 멈추는 명령도 fencing 아래에서 끝나게")
        void pendingCommandsMoveOnlyByAClaimThatIncrementsTheToken() {
            for (CommandEvent event : CommandEvent.values()) {
                // when
                Transition next = TransitionTable.next(StoredState.PENDING, event);
                if (next.outcome() != Transition.Outcome.MOVE) {
                    continue;
                }

                // then: 멈출 명령만 멈춤(HALTED)으로 가고, 나머지는 처리 중(CLAIMED)으로 간다. 어느 쪽이든 토큰을 올린다
                StoredState expectedTo = event == CommandEvent.CLAIM_WITH_GATE_HALTED ? StoredState.HALTED : StoredState.CLAIMED;
                assertThat(event).as("PENDING을 움직인 사건")
                        .isIn(CommandEvent.CLAIM_WITH_GATE_OPEN, CommandEvent.CLAIM_WITH_GATE_HALTED);
                assertThat(next)
                        .as("PENDING + %s: %s로 가며 토큰을 올리기를 기대했다", event, expectedTo)
                        .isEqualTo(new Transition(Transition.Outcome.MOVE, expectedTo, Set.of(Effect.INCREMENT_LEASE_TOKEN)));
            }
        }

        @Test
        @DisplayName("핸들러는 최종 상태로 들어가는 전이에서만, 그리고 그때마다 실행된다 — G6")
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
                                .as("%s + %s: 최종 상태 %s로 들어가는데 핸들러 실행이 빠졌다", state, event, next.to())
                                .contains(Effect.RUN_HANDLER);
                    } else {
                        assertThat(next.effects())
                                .as("%s + %s: 최종이 아닌 %s로 가는데 핸들러 실행이 붙었다", state, event, next.to())
                                .doesNotContain(Effect.RUN_HANDLER);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ResultCategory.class)
        @DisplayName("분류표(§4.4)가 정한 최종 상태와 전이표(§4.3)가 보내는 상태가 같다 — 두 표가 따로 고쳐져 어긋나지 않게")
        void classificationAndTransitionAgreeOnTheFinalState(ResultCategory category) {
            // when
            Transition next = TransitionTable.next(StoredState.CLAIMED, CommandEvent.of(category));

            // then
            assertThat(next.to()).as("%s 결과를 받은 처리 중 명령이 끝나는 상태", category)
                    .isEqualTo(StoredState.finalStateFor(category));
        }
    }

    @Nested
    @Order(3)
    @DisplayName("TB 결과 분류는")
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
        @DisplayName("전이표의 TB 사건 셋 중 하나가 된다 (§4.4 → §4.3)")
        void becomesATigerBeetleEvent(ResultCategory category, CommandEvent expected) {
            // when
            CommandEvent event = CommandEvent.of(category);

            // then
            assertThat(event).as("%s 분류의 TB 사건", category).isEqualTo(expected);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    /** 표의 표기(손으로 쓴 기대값)대로 전이했다. */
    private static void assertTransition(StoredState current, CommandEvent event, Transition actual, String notation) {
        assertThat(actual).as("%s 상태에서 %s 사건은 표에 '%s'로 적혀 있다", current, event, notation)
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
     * 표의 기대값 표기를 {@link Transition}으로 읽는다. 프로덕션 팩토리({@code Transition.zeroRows()} 등)를 쓰지 않고
     * 값을 직접 만든다. 팩토리를 쓰면 팩토리가 틀렸을 때 기대값도 같이 틀려서 표가 아무것도 검사하지 못한다.
     */
    private static Transition parse(String notation) {
        return switch (notation) {
            case "그대로" -> new Transition(Transition.Outcome.UNCHANGED, null, Set.of());
            case "0행" -> new Transition(Transition.Outcome.ZERO_ROWS, null, Set.of());
            case "불가(모듈 버그)" -> new Transition(Transition.Outcome.IMPOSSIBLE, null, Set.of());
            case "해당없음(생길 수 없는 사건)" -> new Transition(Transition.Outcome.NOT_APPLICABLE, null, Set.of());
            default -> {
                String[] parts = notation.split("\\+");
                Set<Effect> effects = EnumSet.noneOf(Effect.class);
                for (int i = 1; i < parts.length; i++) {
                    effects.add(switch (parts[i]) {
                        case "토큰" -> Effect.INCREMENT_LEASE_TOKEN;
                        case "핸들러" -> Effect.RUN_HANDLER;
                        case "backoff" -> Effect.BACKOFF;
                        case "결과보존" -> Effect.KEEP_KNOWN_RESULT;
                        case "제출기록" -> Effect.RECORD_SUBMISSION;
                        case "실패집계" -> Effect.COUNT_HANDLER_FAILURE;
                        default -> throw new IllegalArgumentException("표기 오류: " + parts[i]);
                    });
                }
                yield new Transition(Transition.Outcome.MOVE, StoredState.valueOf(parts[0]), effects);
            }
        };
    }
}
