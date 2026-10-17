package io.github.ledgerrelay.tigerbeetle;

import com.tigerbeetle.Client;
import com.tigerbeetle.ClientClosedException;
import com.tigerbeetle.ClientEvictedException;
import com.tigerbeetle.ClientReleaseException;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * TB Java 클라이언트 위의 {@link LedgerClient}(03a P3a-9·P3a-10). 묶음 하나를 TB 요청 하나로 보낸다.
 * 클라이언트의 수명은 만든 쪽이 관리하며 이 어댑터는 닫지 않는다. 결과 개수는 검사하지 않는다. 그것은 워커의 판단이다.
 */
public final class TigerBeetleLedgerClient implements LedgerClient {

    private final Client client;

    public TigerBeetleLedgerClient(Client client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<List<LedgerResultCode>> submit(List<Submission> batch) {
        TransferBatch transfers = new TransferBatch(batch.size());
        for (Submission submission : batch) {
            TransferBody body = submission.body();
            transfers.add();
            transfers.setId(UInt128.asBytes(submission.transferId()));
            // post·void는 계좌·ledger·code를 0으로 보내 예약의 값을 물려받는다(ReservationSemanticsTest 장 4)
            transfers.setDebitAccountId(UInt128.asBytes(body.debitAccountId()));
            transfers.setCreditAccountId(UInt128.asBytes(body.creditAccountId()));
            transfers.setAmount(body.amount());
            transfers.setLedger((int) body.ledger());
            transfers.setCode(body.code());
            transfers.setTimeout((int) body.timeoutSeconds());
            transfers.setFlags(flagsOf(body.kind()));
            body.pendingTransferId().ifPresent(pending -> transfers.setPendingId(UInt128.asBytes(pending)));
            transfers.setUserData128(UInt128.asBytes(submission.userData128()));
            transfers.setUserData64(body.userData64());
            transfers.setUserData32(body.userData32());
        }
        CompletableFuture<CreateTransferResultBatch> reply;
        try {
            reply = client.createTransfersAsync(transfers);
        } catch (RuntimeException e) {
            throw unavailableOrAsIs(e);
        }
        return reply.handle((results, failure) -> failure == null
                        ? CompletableFuture.completedFuture(codesOf(results))
                        : CompletableFuture.<List<LedgerResultCode>>failedFuture(unavailableOrAsIs(unwrap(failure))))
                .thenCompose(codes -> codes);
    }

    /**
     * 닫힌 클라이언트, 세션 축출(clients_max 초과), 클라이언트·서버 버전 불일치는 다시 보내도 회복되지 않는다. 일시적 실패와
     * 구분되게 {@link LedgerUnavailableException}으로 감싼다(02d P2d-8). 나머지는 그대로 둔다.
     */
    private static RuntimeException unavailableOrAsIs(Throwable failure) {
        if (failure instanceof ClientClosedException || failure instanceof ClientEvictedException
                || failure instanceof ClientReleaseException) {
            return new LedgerUnavailableException("TB 클라이언트를 더 쓸 수 없습니다: " + failure, failure);
        }
        return failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    static int flagsOf(CommandKind kind) {
        return switch (kind) {
            case TRANSFER -> TransferFlags.NONE;
            case RESERVE -> TransferFlags.PENDING;
            case POST -> TransferFlags.POST_PENDING_TRANSFER;
            case VOID -> TransferFlags.VOID_PENDING_TRANSFER;
        };
    }

    /** TB 0.17은 이벤트마다 결과를 요청 순서대로 하나씩 낸다. 두 enum의 이름이 같다는 것은 ResultCodeGuardTest가 지킨다. */
    private static List<LedgerResultCode> codesOf(CreateTransferResultBatch results) {
        List<LedgerResultCode> codes = new ArrayList<>(results.getLength());
        while (results.next()) {
            codes.add(LedgerResultCode.valueOf(results.getStatus().name()));
        }
        return codes;
    }
}
