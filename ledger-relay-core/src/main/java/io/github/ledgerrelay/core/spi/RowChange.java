package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import java.time.Duration;
import java.util.Objects;

/**
 * 조건부 쓰기 하나(G5). 행이 {@code expectedState}이고 토큰이 {@code expectedToken}일 때만 {@code next}로 바꾼다.
 * jdbc: {@code UPDATE … WHERE id = ? AND state = ? AND lease_token = ?}.
 *
 * @param next  상태 기계가 계산한 다음 행. 저장소는 그대로 쓴다
 * @param stamp 함께 바꿀 시각 컬럼. 시각은 저장소의 시계(PG now())로 정한다(§5.2)
 */
public record RowChange(long rowId, StoredState expectedState, long expectedToken, CommandRow next, Stamp stamp) {

    public RowChange {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(stamp, "stamp");
    }

    /** 쓰기와 함께 찍는 시각. */
    public sealed interface Stamp {

        /** claim: lease_expires_at = now + lease. */
        record LeaseFor(Duration lease) implements Stamp {
        }

        /** 제출 기록: first_submitted_at이 비어 있으면 now. */
        record FirstSubmission() implements Stamp {
        }

        /** 되돌림·핸들러 실패: next_attempt_at = now + backoff. */
        record RetryAfter(Duration backoff) implements Stamp {
        }

        /** 최종 상태: completed_at = now. */
        record Completion() implements Stamp {
        }
    }
}
