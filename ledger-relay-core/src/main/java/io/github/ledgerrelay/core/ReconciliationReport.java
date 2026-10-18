package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * 대사 한 페이지의 결과(운영자 포트).
 *
 * @param checked      확인한 명령 수. 충돌(CONFLICT)로 끝난 명령은 세지 않는다
 * @param matched      그중 PG와 TB가 맞은 수
 * @param mismatches   어긋난 명령. 최대 {@link #MAX_MISMATCHES}개이고, 넘는 수는 {@code checked - matched}로 안다
 * @param lastPosition 이 페이지에서 본 마지막 행의 위치. 다음 페이지를 읽을 때 넘긴다
 */
public record ReconciliationReport(long checked, long matched, List<Mismatch> mismatches, long lastPosition) {

    public static final int MAX_MISMATCHES = 100;

    public ReconciliationReport {
        mismatches = List.copyOf(mismatches);
    }

    /** 어긋난 명령 하나. */
    public record Mismatch(String commandId, BigInteger transferId, Kind kind) {

        public Mismatch {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(transferId, "transferId");
            Objects.requireNonNull(kind, "kind");
        }

        /** 어떻게 어긋났는가. */
        public enum Kind {
            /** PG는 TB에서 적용됐다고 기록했는데 TB에 그 이체가 없다. */
            MISSING_IN_LEDGER,
            /** PG는 거절·건너뜀으로 기록했는데 TB에 그 이체가 있다. */
            UNEXPECTED_IN_LEDGER,
            /** TB에 있지만 종류·계좌·금액 등이 PG의 명령과 다르다. */
            DIFFERENT
        }
    }
}
