package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.LedgerResultCode;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** TB에 이체를 묶어 보낸다(§5.3). 3단계에 tigerbeetle 모듈이 구현한다. */
public interface LedgerClient {

    /**
     * @return 요청과 같은 순서의 결과. TB 클라이언트에는 timeout이 없으므로 이 future는 오래 끝나지 않을 수 있다.
     *         호출자는 submit-timeout으로 기다림을 끊되, in-flight 슬롯은 future가 실제로 끝날 때 반납한다
     */
    CompletableFuture<List<LedgerResultCode>> submit(List<Submission> batch);

    /**
     * 이체를 ID로 조회한다(대사). TB에 있는 것만, 순서와 상관없이 돌려준다.
     */
    CompletableFuture<List<LedgerTransfer>> lookup(List<BigInteger> transferIds);
}
