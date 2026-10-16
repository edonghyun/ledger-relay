package io.github.ledgerrelay.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ledgerrelay.core.Transition.Effect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("상태 전이 — 스펙 §4.3 전이표, G5·G6")
class TransitionTableTest {

    /**
     * 스펙 §4.3 전이표를 칸 하나에 한 줄로 옮긴 것. 기대값 표기는 이렇다.
     * 상태 이름(+토큰 +핸들러 +backoff +결과보존) = 그 상태로 이동하며 해당 효과를 낸다.
     * 그대로 = 게이트가 닫혀 아무 일도 없다. 0행 = fenced UPDATE가 0행을 갱신한다.
     * 불가 = 모듈이 이 사건을 만들면 버그다. 해당없음 = 이 상태에서는 생길 수 없는 사건이다.
     */
    static final String TRANSITION_TABLE = """
            # PENDING
            PENDING, CLAIM_WITH_GATE_OPEN,               CLAIMED+토큰
            PENDING, CLAIM_WITH_GATE_CLOSED,             그대로
            PENDING, CLAIM_WITH_RESERVATION_NOT_APPLIED, SKIPPED+핸들러
            PENDING, TB_APPLIED,                         불가
            PENDING, TB_REJECTED,                        불가
            PENDING, TB_CONFLICT,                        불가
            PENDING, NO_REPLY_OR_ERROR,                  불가
            PENDING, HANDLER_FAILED,                     불가
            PENDING, LEASE_EXPIRED,                      해당없음
            PENDING, STALE_TOKEN_WRITE,                  0행
            # CLAIMED: claim은 lease가 만료되기 전이면 불가
            CLAIMED, CLAIM_WITH_GATE_OPEN,               불가
            CLAIMED, CLAIM_WITH_GATE_CLOSED,             불가
            CLAIMED, CLAIM_WITH_RESERVATION_NOT_APPLIED, 불가
            CLAIMED, TB_APPLIED,                         APPLIED+핸들러
            CLAIMED, TB_REJECTED,                        REJECTED+핸들러
            CLAIMED, TB_CONFLICT,                        CONFLICT+핸들러
            CLAIMED, NO_REPLY_OR_ERROR,                  PENDING+backoff
            CLAIMED, HANDLER_FAILED,                     PENDING+backoff+결과보존
            CLAIMED, LEASE_EXPIRED,                      CLAIMED+토큰
            CLAIMED, STALE_TOKEN_WRITE,                  0행
            # 최종 상태 4개는 같은 줄을 갖는다
            APPLIED, CLAIM_WITH_GATE_OPEN,               불가
            APPLIED, CLAIM_WITH_GATE_CLOSED,             불가
            APPLIED, CLAIM_WITH_RESERVATION_NOT_APPLIED, 불가
            APPLIED, TB_APPLIED,                         0행
            APPLIED, TB_REJECTED,                        0행
            APPLIED, TB_CONFLICT,                        0행
            APPLIED, NO_REPLY_OR_ERROR,                  0행
            APPLIED, HANDLER_FAILED,                     해당없음
            APPLIED, LEASE_EXPIRED,                      해당없음
            APPLIED, STALE_TOKEN_WRITE,                  0행
            REJECTED, CLAIM_WITH_GATE_OPEN,               불가
            REJECTED, CLAIM_WITH_GATE_CLOSED,             불가
            REJECTED, CLAIM_WITH_RESERVATION_NOT_APPLIED, 불가
            REJECTED, TB_APPLIED,                         0행
            REJECTED, TB_REJECTED,                        0행
            REJECTED, TB_CONFLICT,                        0행
            REJECTED, NO_REPLY_OR_ERROR,                  0행
            REJECTED, HANDLER_FAILED,                     해당없음
            REJECTED, LEASE_EXPIRED,                      해당없음
            REJECTED, STALE_TOKEN_WRITE,                  0행
            CONFLICT, CLAIM_WITH_GATE_OPEN,               불가
            CONFLICT, CLAIM_WITH_GATE_CLOSED,             불가
            CONFLICT, CLAIM_WITH_RESERVATION_NOT_APPLIED, 불가
            CONFLICT, TB_APPLIED,                         0행
            CONFLICT, TB_REJECTED,                        0행
            CONFLICT, TB_CONFLICT,                        0행
            CONFLICT, NO_REPLY_OR_ERROR,                  0행
            CONFLICT, HANDLER_FAILED,                     해당없음
            CONFLICT, LEASE_EXPIRED,                      해당없음
            CONFLICT, STALE_TOKEN_WRITE,                  0행
            SKIPPED, CLAIM_WITH_GATE_OPEN,               불가
            SKIPPED, CLAIM_WITH_GATE_CLOSED,             불가
            SKIPPED, CLAIM_WITH_RESERVATION_NOT_APPLIED, 불가
            SKIPPED, TB_APPLIED,                         0행
            SKIPPED, TB_REJECTED,                        0행
            SKIPPED, TB_CONFLICT,                        0행
            SKIPPED, NO_REPLY_OR_ERROR,                  0행
            SKIPPED, HANDLER_FAILED,                     해당없음
            SKIPPED, LEASE_EXPIRED,                      해당없음
            SKIPPED, STALE_TOKEN_WRITE,                  0행
            """;

    @Nested
    @DisplayName("전이표의 칸마다")
    class EveryCell {

        @ParameterizedTest(name = "{0} + {1} → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = TRANSITION_TABLE)
        @DisplayName("표에 적힌 결과가 나온다")
        void producesTheTabledTransition(CommandState current, CommandEvent event, String expected) {
            // when
            Transition next = TransitionTable.next(current, event);

            // then
            assertThat(next).as("%s 상태에서 %s 사건", current, event).isEqualTo(parse(expected));
        }

        @Test
        @DisplayName("전이표는 모든 상태와 사건의 조합을 정확히 한 번씩 담는다 — 빈 칸이 없게")
        void tableCoversEveryPairOnce() {
            // given
            List<String> pairsInTheTable = rows().stream().map(row -> row[0] + "," + row[1]).toList();
            List<String> allPairs = new ArrayList<>();
            for (CommandState state : CommandState.values()) {
                for (CommandEvent event : CommandEvent.values()) {
                    allPairs.add(state + "," + event);
                }
            }

            // then
            assertThat(pairsInTheTable).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(allPairs);
        }
    }

    @Nested
    @DisplayName("표 전체를 놓고 보면")
    class TableWideRules {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CommandState.class, names = {"APPLIED", "REJECTED", "CONFLICT", "SKIPPED"})
        @DisplayName("G5: 최종 상태는 어떤 사건에도 다른 상태로 바뀌지 않는다")
        void finalStatesNeverMove(CommandState finalState) {
            for (CommandEvent event : CommandEvent.values()) {
                assertThat(TransitionTable.next(finalState, event).outcome())
                        .as("%s 상태에서 %s 사건", finalState, event)
                        .isNotEqualTo(Transition.Outcome.MOVE);
            }
        }

        @Test
        @DisplayName("G6: 핸들러는 최종 상태로 들어가는 전이에서만, 그리고 그때마다 실행된다")
        void handlerRunsExactlyOnEnteringAFinalState() {
            for (CommandState state : CommandState.values()) {
                for (CommandEvent event : CommandEvent.values()) {
                    // when
                    Transition next = TransitionTable.next(state, event);
                    if (next.outcome() != Transition.Outcome.MOVE) {
                        continue;
                    }

                    // then
                    assertThat(next.effects().contains(Effect.RUN_HANDLER))
                            .as("%s + %s → %s: 핸들러 실행 여부", state, event, next.to())
                            .isEqualTo(next.to().isFinal());
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ResultCategory.class)
        @DisplayName("분류표(§4.4)가 정한 최종 상태와 전이표(§4.3)가 보내는 상태가 같다 — 두 표가 따로 고쳐져 어긋나지 않게")
        void classificationAndTransitionAgreeOnTheFinalState(ResultCategory category) {
            // when
            Transition next = TransitionTable.next(CommandState.CLAIMED, CommandEvent.of(category));

            // then
            assertThat(next.to()).as("%s 결과를 받은 처리 중 명령이 끝나는 상태", category)
                    .isEqualTo(category.finalState());
        }
    }

    @Nested
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
            assertThat(CommandEvent.of(category)).isEqualTo(expected);
        }
    }

    // ── 이 파일에서만 쓰는 말 ──────────────────────────────────────────

    private static List<String[]> rows() {
        return TRANSITION_TABLE.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> Arrays.stream(line.split(",")).map(String::strip).toArray(String[]::new))
                .toList();
    }

    /** 표의 기대값 표기를 {@link Transition}으로 읽는다. */
    private static Transition parse(String notation) {
        return switch (notation) {
            case "그대로" -> Transition.unchanged();
            case "0행" -> Transition.zeroRows();
            case "불가" -> Transition.impossible();
            case "해당없음" -> Transition.notApplicable();
            default -> {
                String[] parts = notation.split("\\+");
                Set<Effect> effects = EnumSet.noneOf(Effect.class);
                for (int i = 1; i < parts.length; i++) {
                    effects.add(switch (parts[i]) {
                        case "토큰" -> Effect.INCREMENT_LEASE_TOKEN;
                        case "핸들러" -> Effect.RUN_HANDLER;
                        case "backoff" -> Effect.BACKOFF;
                        case "결과보존" -> Effect.KEEP_KNOWN_RESULT;
                        default -> throw new IllegalArgumentException("표기 오류: " + parts[i]);
                    });
                }
                yield Transition.moveTo(CommandState.valueOf(parts[0]), effects.toArray(Effect[]::new));
            }
        };
    }
}
