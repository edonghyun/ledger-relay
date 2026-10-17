package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.Submission;
import java.util.Objects;
import java.util.Optional;

/**
 * claim으로 잡은 명령 하나. 워커는 이것으로 다음 일을 고른다.
 * {@link #skip()}이면 TB에 보내지 않고 {@code skip}한다. {@link #knownResult()}가 있으면 TB에 보내지 않고 그 결과로
 * {@code complete}한다. 둘 다 아니면 {@code markSubmitted} 뒤 TB에 보낸다.
 *
 * @param leaseToken 이 claim이 받은 토큰. 이후의 모든 쓰기에 내민다
 * @param skip       post·void가 참조한 예약이 적용되지 않고 끝났다(§5.2)
 */
public record ClaimedCommand(
        long rowId,
        String commandId,
        String orderingKey,
        long leaseToken,
        boolean skip,
        Optional<LedgerResultCode> knownResult,
        Submission submission) {

    public ClaimedCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(knownResult, "knownResult");
        Objects.requireNonNull(submission, "submission");
    }
}
