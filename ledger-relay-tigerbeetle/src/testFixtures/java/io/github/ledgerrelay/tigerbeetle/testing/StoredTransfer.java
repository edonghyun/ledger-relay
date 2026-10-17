package io.github.ledgerrelay.tigerbeetle.testing;

import java.math.BigInteger;

/**
 * TB가 실제로 저장한 이체. 확정·해제가 예약에서 물려받은 값과, 전액 확정의 실제 금액을 확인할 때 쓴다.
 *
 * @param timestamp TB 클러스터가 이체를 적용한 시각(ns)
 */
public record StoredTransfer(BigInteger amount, byte[] debitAccountId, byte[] creditAccountId, int ledger, int code,
        BigInteger userData128, long userData64, int userData32, long timeoutSeconds, long timestamp) {
}
