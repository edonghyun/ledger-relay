package io.github.ledgerrelay.tigerbeetle.testing;

import static com.tigerbeetle.CreateTransferStatus.Created;
import static com.tigerbeetle.CreateTransferStatus.Exists;
import static com.tigerbeetle.CreateTransferStatus.ExistsWithDifferentAmount;
import static com.tigerbeetle.CreateTransferStatus.IdAlreadyFailed;

import com.tigerbeetle.CreateTransferStatus;
import java.time.Duration;

/**
 * then 단계의 말. 메서드 하나가 일어난 효과 하나를 확인하고, 실패하면 같은 말로 무엇이 달랐는지 알려 준다.
 * TB 원래 결과 코드는 메시지의 괄호 안에 남긴다.
 */
public final class LedgerAssertions {

    private LedgerAssertions() {
    }

    public static void assertCreated(CreateTransferStatus result) {
        expect(result, Created, "새로 기록되기");
    }

    public static void assertAlreadyRecorded(CreateTransferStatus result) {
        expect(result, Exists, "같은 ID·같은 본문의 이체가 이미 기록된 것으로 인정되기");
    }

    public static void assertConflictOnAmount(CreateTransferStatus result) {
        expect(result, ExistsWithDifferentAmount, "같은 ID인데 금액이 달라 내용 충돌로 거부되기");
    }

    /** {@code conflict}는 어느 필드가 달랐는지를 뜻하는 {@code ExistsWithDifferent…} 코드다. */
    public static void assertContentConflict(CreateTransferStatus result, CreateTransferStatus conflict) {
        if (!conflict.name().startsWith("ExistsWithDifferent")) {
            throw new IllegalArgumentException(conflict + "는 내용 충돌 코드(ExistsWithDifferent…)가 아닙니다");
        }
        expect(result, conflict, "같은 ID인데 내용이 달라 충돌로 거부되기");
    }

    public static void assertRejectedBecauseIdAlreadyFailed(CreateTransferStatus result) {
        expect(result, IdAlreadyFailed, "이 ID가 이미 실패했으므로 다시 판정되지 않고 거부되기");
    }

    public static void assertRejectedFor(CreateTransferStatus result, CreateTransferStatus reason) {
        expect(result, reason, "이 사유로 거부되기");
    }

    public static void assertMovedExactly(TigerBeetleTestLedger ledger, Account from, Account to, long amount) {
        Balance source = ledger.balanceOf(from);
        Balance destination = ledger.balanceOf(to);
        if (source.debitsPosted() != amount || destination.creditsPosted() != amount) {
            throw new AssertionError(from + " → " + to + "로 정확히 " + amount + "이 움직이기를 기대했지만, "
                    + from + "은 [" + source + "], " + to + "은 [" + destination + "]입니다");
        }
    }

    public static void assertNothingMoved(TigerBeetleTestLedger ledger, Account account) {
        Balance balance = ledger.balanceOf(account);
        if (!balance.isUntouched()) {
            throw new AssertionError(account + " 계좌에 아무 변화가 없기를 기대했지만 [" + balance + "]입니다");
        }
    }

    /** "아무 일도 없어야 한다"를 관찰하므로 고정된 관찰 시간을 쓴다(docs/testing.md §8). */
    public static void assertStillWaitingAfter(PendingTransfer pending, Duration window) {
        try {
            Thread.sleep(window.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (pending.hasReply()) {
            throw new AssertionError(pending.transfer() + " 이체가 " + window
                    + " 동안 응답 없이 대기하기를 기대했지만, 이미 응답이 왔습니다");
        }
    }

    private static void expect(CreateTransferStatus actual, CreateTransferStatus expected, String meaning) {
        if (actual != expected) {
            throw new AssertionError(meaning + "(" + expected + ")를 기대했지만 실제 결과는 " + actual + describe(actual));
        }
    }

    private static String describe(CreateTransferStatus status) {
        return switch (status) {
            case Created -> " — 처음 보내는 이체로 기록됨";
            case Exists -> " — 같은 이체가 이미 기록되어 있음";
            case IdAlreadyFailed -> " — 이 ID는 이전에 실패함";
            default -> "";
        };
    }
}
