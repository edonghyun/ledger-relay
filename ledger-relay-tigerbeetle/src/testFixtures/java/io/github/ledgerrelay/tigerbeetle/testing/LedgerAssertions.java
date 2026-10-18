package io.github.ledgerrelay.tigerbeetle.testing;

import static com.tigerbeetle.CreateTransferStatus.Created;
import static com.tigerbeetle.CreateTransferStatus.Exists;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentAmount;
import static com.tigerbeetle.CreateTransferStatus.IdAlreadyFailed;

import com.tigerbeetle.CreateTransferStatus;
import java.time.Duration;

/**
 * Vocabulary for the then step. Each method checks one effect that happened, and on failure it says in the same words
 * what was different. The original TB result code stays in parentheses in the message.
 */
public final class LedgerAssertions {

    private LedgerAssertions() {
    }

    public static void assertCreated(CreateTransferStatus result) {
        expect(result, Created, "the transfer to be newly recorded");
    }

    public static void assertAlreadyRecorded(CreateTransferStatus result) {
        expect(result, Exists, "a transfer with the same id and body to be recognized as already recorded");
    }

    public static void assertConflictOnAmount(CreateTransferStatus result) {
        expect(result, ExistsWithDifferentAmount,
                "a rejection as a content conflict because the amount differs for the same id");
    }

    /** {@code conflict} is the {@code ExistsWithDifferent…} code that names the field that differed. */
    public static void assertContentConflict(CreateTransferStatus result, CreateTransferStatus conflict) {
        if (!conflict.name().startsWith("ExistsWithDifferent")) {
            throw new IllegalArgumentException(conflict + " is not a content conflict code (ExistsWithDifferent…)");
        }
        expect(result, conflict, "a rejection as a conflict because the content differs for the same id");
    }

    public static void assertRejectedBecauseIdAlreadyFailed(CreateTransferStatus result) {
        expect(result, IdAlreadyFailed, "a rejection without a new decision because this id already failed");
    }

    public static void assertRejectedFor(CreateTransferStatus result, CreateTransferStatus reason) {
        expect(result, reason, "a rejection for this reason");
    }

    public static void assertMovedExactly(TigerBeetleTestLedger ledger, Account from, Account to, long amount) {
        Balance source = ledger.balanceOf(from);
        Balance destination = ledger.balanceOf(to);
        if (source.debitsPosted() != amount || destination.creditsPosted() != amount) {
            throw new AssertionError("Expected exactly " + amount + " to move " + from + " → " + to + ", but "
                    + from + " is [" + source + "] and " + to + " is [" + destination + "]");
        }
    }

    public static void assertNothingMoved(TigerBeetleTestLedger ledger, Account account) {
        Balance balance = ledger.balanceOf(account);
        if (!balance.isUntouched()) {
            throw new AssertionError("Expected no change on account " + account + ", but it is [" + balance + "]");
        }
    }

    /** Exactly {@code amount} is held from {@code from} to {@code to}, and nothing is posted yet. */
    public static void assertReserved(TigerBeetleTestLedger ledger, Account from, Account to, long amount) {
        Balance source = ledger.balanceOf(from);
        Balance destination = ledger.balanceOf(to);
        if (source.debitsPending() != amount || destination.creditsPending() != amount
                || source.debitsPosted() != 0 || destination.creditsPosted() != 0) {
            throw new AssertionError("Expected exactly " + amount + " held " + from + " → " + to
                    + " with nothing posted, but " + from + " is [" + source + "] and " + to
                    + " is [" + destination + "]");
        }
    }

    /** Exactly {@code posted} is posted from {@code from} to {@code to}, and no held amount remains on either side. */
    public static void assertPostedAndReleased(TigerBeetleTestLedger ledger, Account from, Account to, long posted) {
        Balance source = ledger.balanceOf(from);
        Balance destination = ledger.balanceOf(to);
        if (source.debitsPosted() != posted || destination.creditsPosted() != posted
                || source.debitsPending() != 0 || destination.creditsPending() != 0) {
            throw new AssertionError("Expected exactly " + posted + " to be posted " + from + " → " + to + " and all "
                    + "holds released, but " + from + " is [" + source + "] and " + to + " is [" + destination + "]");
        }
    }

    /** Observes that "nothing happens", so it uses a fixed observation window (docs/testing.md). */
    public static void assertStillWaitingAfter(PendingTransfer pending, Duration window) {
        try {
            Thread.sleep(window.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (pending.hasReply()) {
            throw new AssertionError("Expected transfer " + pending.transfer() + " to wait without a reply for "
                    + window + ", but a reply already arrived");
        }
    }

    private static void expect(CreateTransferStatus actual, CreateTransferStatus expected, String meaning) {
        if (actual != expected) {
            throw new AssertionError("Expected " + meaning + " (" + expected + "), but the actual result is " + actual
                    + describe(actual));
        }
    }

    private static String describe(CreateTransferStatus status) {
        return switch (status) {
            case Created -> " — recorded as a transfer sent for the first time";
            case Exists -> " — the same transfer is already recorded";
            case IdAlreadyFailed -> " — this id failed before";
            default -> "";
        };
    }
}
