package io.github.ledgerrelay.core.domain;

import io.github.ledgerrelay.core.LedgerResultCode;
import java.util.Optional;

/** 명령 행에 일어나는 일 하나. claim은 토큰을 받고, 나머지 쓰기는 claim 때 받은 토큰을 내민다. */
public sealed interface Step {

    /**
     * claim(§5.2). 토큰을 내밀지 않는다.
     *
     * @param gate         접수된(PENDING) 명령에 대한 게이트 결정
     * @param leaseExpired 처리 중(CLAIMED) 명령의 lease가 끝났는가. 저장소가 PG 시각으로 판단한다
     */
    record Claim(GateDecision gate, boolean leaseExpired) implements Step {
    }

    /** claim 때 받은 토큰으로 하는 쓰기. jdbc에서는 {@code WHERE lease_token = ? AND state = 'CLAIMED'}다. */
    sealed interface Write extends Step {
        long token();
    }

    /** TB에 보내기 직전의 제출 기록. */
    record MarkSubmitted(long token) implements Write {
    }

    /** TB 결과로 끝낸다. 이미 아는 결과가 있으면 그 코드여야 한다. */
    record Complete(long token, LedgerResultCode code) implements Write {
    }

    /** 참조한 예약이 적용되지 않고 끝나 건너뛴다. */
    record Skip(long token) implements Write {
    }

    /** 응답이 없거나 예외가 나서 되돌린다. */
    record Release(long token) implements Write {
    }

    /** 결과 반영 핸들러가 실패했다. 받은 TB 결과(건너뜀이면 없음)를 다음 claim에 넘긴다. */
    record HandlerFailed(long token, Optional<LedgerResultCode> knownResult) implements Write {
    }
}
