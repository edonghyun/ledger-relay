package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.LedgerResultCode;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sends transfers to TB in batches. The implementation is {@code TigerBeetleLedgerClient} in ledger-relay-tigerbeetle.
 */
public interface LedgerClient {

    /**
     * @return the results in the same order as the request. The TB client has no timeout, so this future may not finish
     *         for a long time. The caller stops waiting at submit-timeout, but returns the in-flight slots only when
     * the
     *         future actually finishes
     */
    CompletableFuture<List<LedgerResultCode>> submit(List<Submission> batch);

    /**
     * Looks up transfers by id (reconciliation). Returns only those that exist in TB, in any order.
     */
    CompletableFuture<List<LedgerTransfer>> lookup(List<BigInteger> transferIds);
}
