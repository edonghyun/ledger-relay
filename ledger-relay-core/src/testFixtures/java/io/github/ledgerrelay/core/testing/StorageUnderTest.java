package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Duration;

/** 계약 테스트가 돌릴 저장소 한 벌. 구현(메모리, PostgreSQL)마다 하나를 만든다. */
public interface StorageUnderTest {

    CommandStore store();

    Transactions transactions();

    /** 저장소의 시계를 앞으로 돌린다(lease 만료, backoff 경과). PG 구현은 시각 컬럼을 그만큼 당긴다. */
    void elapse(Duration duration);

    /** 트랜잭션 밖에서 {@link Transactions#requireActive()}를 부르면 나는 예외 타입(P2a-13). */
    Class<? extends RuntimeException> outsideTransactionError();
}
