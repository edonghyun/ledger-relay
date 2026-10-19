package io.github.ledgerrelay.tigerbeetle;

import com.tigerbeetle.Client;
import com.tigerbeetle.ClientClosedException;
import com.tigerbeetle.ClientEvictedException;
import com.tigerbeetle.ClientReleaseException;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TooMuchDataException;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.LedgerTransfer;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link LedgerClient} on top of the TB Java client. Sends one batch as one TB request.
 * The creator of the client manages its lifetime; this adapter does not close it. It does not check the number of
 * results; that is the worker's decision.
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
            // post and void send account, ledger and code as 0 so they inherit the reservation's values
            // (ReservationSemanticsTest section 4)
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
     * Lookup for reconciliation. Ids that TB does not have are absent from the result. The kind is derived from the
     * flags (pending → RESERVE, post pending → POST, void pending → VOID).
     */
    @Override
    public CompletableFuture<List<LedgerTransfer>> lookup(List<BigInteger> transferIds) {
        IdBatch ids = new IdBatch(transferIds.size());
        transferIds.forEach(id -> ids.add(UInt128.asBytes(id)));
        CompletableFuture<TransferBatch> reply;
        try {
            reply = client.lookupTransfersAsync(ids);
        } catch (RuntimeException e) {
            throw unavailableOrAsIs(e);
        }
        return reply.handle((found, failure) -> failure == null
                        ? CompletableFuture.completedFuture(transfersOf(found))
                        : CompletableFuture.<List<LedgerTransfer>>failedFuture(unavailableOrAsIs(unwrap(failure))))
                .thenCompose(transfers -> transfers);
    }

    private static List<LedgerTransfer> transfersOf(TransferBatch found) {
        List<LedgerTransfer> transfers = new ArrayList<>(found.getLength());
        found.beforeFirst();
        while (found.next()) {
            transfers.add(new LedgerTransfer(UInt128.asBigInteger(found.getId()),
                    UInt128.asBigInteger(found.getDebitAccountId()), UInt128.asBigInteger(found.getCreditAccountId()),
                    found.getAmount(), Integer.toUnsignedLong(found.getLedger()), found.getCode(),
                    kindOf(found.getFlags())));
        }
        return transfers;
    }

    private static CommandKind kindOf(int flags) {
        if ((flags & TransferFlags.PENDING) != 0) {
            return CommandKind.RESERVE;
        }
        if ((flags & TransferFlags.POST_PENDING_TRANSFER) != 0) {
            return CommandKind.POST;
        }
        if ((flags & TransferFlags.VOID_PENDING_TRANSFER) != 0) {
            return CommandKind.VOID;
        }
        return CommandKind.TRANSFER;
    }

    /**
     * A closed client, session eviction (more than clients_max) and a client/server version mismatch do not recover on
     * resend. Neither does a batch larger than the cluster takes in one request: the resend is the same size (F48).
     * They are wrapped in {@link LedgerUnavailableException} so they can be told apart from transient failures.
     * Everything else is left as is.
     */
    private static RuntimeException unavailableOrAsIs(Throwable failure) {
        if (failure instanceof ClientClosedException || failure instanceof ClientEvictedException
                || failure instanceof ClientReleaseException) {
            return new LedgerUnavailableException("The TB client can no longer be used: " + failure, failure);
        }
        if (failure instanceof TooMuchDataException) {
            return new LedgerUnavailableException("The cluster refuses a request this large; lower batch-size"
                    + " (a replica started with --development takes at most 253 transfers): " + failure, failure);
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

    /**
     * TB 0.17 returns one result per event, in request order. ResultCodeGuardTest ensures the two enums have the same
     * names.
     */
    private static List<LedgerResultCode> codesOf(CreateTransferResultBatch results) {
        List<LedgerResultCode> codes = new ArrayList<>(results.getLength());
        while (results.next()) {
            codes.add(LedgerResultCode.valueOf(results.getStatus().name()));
        }
        return codes;
    }
}
