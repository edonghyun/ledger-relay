package io.github.ledgerrelay.core.application;

import java.time.Duration;
import java.util.Objects;

/**
 * 워커 한 바퀴의 설정(스펙 §6 {@code worker.*}). 기본값은 03b의 설정 바인딩이 갖는다.
 *
 * @param batchSize     한 바퀴에 claim할 최대 명령 수이자 한 TB 요청의 최대 이체 수
 * @param maxInFlight   프로세스 전체에서 동시에 TB에 가 있을 수 있는 이체 수(§5.3-2)
 * @param lease         claim한 명령을 쥐는 시간(§5.2). submitTimeout의 두 배 이상이어야 하고, 한 바퀴의 결과 쓰기(명령마다
 *                      핸들러 포함 트랜잭션 하나)까지 덮도록 batchSize에 맞춰 잡는다
 * @param submitTimeout TB 응답을 기다리는 상한. 지나면 되돌린다(§5.3-4)
 * @param pollTimeout   in-flight 슬롯을 기다리는 상한. 못 잡으면 그 바퀴는 claim하지 않는다(03a P3a-2)
 */
public record WorkerSettings(int batchSize, int maxInFlight, Duration lease, Duration submitTimeout,
        Duration pollTimeout) {

    /** TB 요청 하나에 담을 수 있는 최대 이벤트 수(TB 0.17, 1MiB 메시지). */
    public static final int TB_MAX_BATCH = 8189;

    public WorkerSettings {
        if (batchSize < 1 || batchSize > TB_MAX_BATCH) {
            throw new IllegalArgumentException("batchSize는 1 이상 " + TB_MAX_BATCH + " 이하여야 합니다: " + batchSize);
        }
        if (maxInFlight < batchSize) {
            throw new IllegalArgumentException("maxInFlight(" + maxInFlight + ")는 batchSize(" + batchSize
                    + ")보다 작을 수 없습니다. 그러면 워커가 슬롯을 영원히 잡지 못합니다");
        }
        requirePositive("lease", lease);
        requirePositive("submitTimeout", submitTimeout);
        requirePositive("pollTimeout", pollTimeout);
        // lease는 claim에서 시작해 제출 기록, 응답 기다림, 명령마다의 결과 쓰기를 모두 덮어야 한다. 짧으면 기다리는 동안
        // 다른 워커가 같은 명령을 다시 잡아 보내고, TB가 기억하는 거절은 IdAlreadyFailed로 덮인다
        if (lease.compareTo(submitTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("lease(" + lease + ")는 submitTimeout(" + submitTimeout
                    + ")의 두 배 이상이어야 합니다. 응답을 기다리고 결과를 쓰는 동안 lease가 끝나면 안 됩니다");
        }
    }

    private static void requirePositive(String field, Duration value) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + "는 0보다 길어야 합니다: " + value);
        }
    }
}
