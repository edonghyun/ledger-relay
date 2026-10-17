package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.LedgerResultCode;
import java.util.Objects;
import java.util.Optional;

/**
 * 명령 행에서 상태 기계가 보는 부분(스펙 §5.6). 시각 컬럼(lease_expires_at, next_attempt_at, completed_at)은
 * 저장소가 PG의 now()로 다룬다(§5.2). 상태 기계는 시계를 모른다.
 *
 * @param knownResult 핸들러가 실패해 다음 claim으로 넘긴 TB 결과(§5.4)
 * @param resultCode  명령을 끝낸 TB 결과. 건너뜀으로 끝났으면 없다
 */
public record CommandRow(
        StoredState state,
        long leaseToken,
        long submitCount,
        Optional<LedgerResultCode> knownResult,
        Optional<LedgerResultCode> resultCode,
        int handlerFailures) {

    public CommandRow {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(knownResult, "knownResult");
        Objects.requireNonNull(resultCode, "resultCode");
    }

    /** 막 접수된 행. */
    public static CommandRow accepted() {
        return new CommandRow(StoredState.PENDING, 0, 0, Optional.empty(), Optional.empty(), 0);
    }
}
