package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 다른 스레드에서 연 트랜잭션. 동시성 장이 "다른 워커·다른 앱 트랜잭션"을 결정적으로 만들 때 쓴다(P2b-4).
 * 일을 마친 뒤에도 {@link #commit()}이나 {@link #rollback()}을 부를 때까지 트랜잭션을 연 채로 기다린다.
 * 그래서 그 트랜잭션이 잡은 행 잠금이 테스트가 정한 순간까지 살아 있다. 상한(10초) 안에 지시가 없으면 롤백한다.
 */
public final class OtherTransaction<T> implements AutoCloseable {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    private final CountDownLatch workDone = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private final CountDownLatch transactionDone = new CountDownLatch(1);
    private volatile boolean rollingBack;
    private volatile T result;
    private volatile Throwable workFailure;
    private volatile Throwable endFailure;

    private final Duration limit;

    private OtherTransaction(Duration limit) {
        this.limit = limit;
    }

    /** 새 스레드에서 트랜잭션을 열어 {@code work}를 실행한다. 바로 돌아온다. */
    public static <T> OtherTransaction<T> start(Transactions transactions, Supplier<T> work) {
        return start(transactions, work, LIMIT);
    }

    /** 상한을 정해 연다. 이 클래스의 테스트가 상한이 지난 뒤의 동작을 짧게 확인할 때 쓴다. */
    static <T> OtherTransaction<T> start(Transactions transactions, Supplier<T> work, Duration limit) {
        OtherTransaction<T> other = new OtherTransaction<>(limit);
        Thread thread = new Thread(() -> other.run(transactions, work), "other-transaction");
        thread.setDaemon(true);
        thread.start();
        return other;
    }

    /** 일이 끝나기를 기다려 결과를 돌려준다. 일이 던졌으면 그 예외를 던진다. */
    public T awaitWork() {
        await(workDone, "다른 트랜잭션의 일이");
        if (workFailure != null) {
            throw asUnchecked(workFailure);
        }
        return result;
    }

    /** 관찰 시간 동안 일이 끝나지 않았음을 확인한다 — 무언가에 막혀 있다. */
    public void assertStillWorkingAfter(Duration observation) {
        try {
            if (workDone.await(observation.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("다른 트랜잭션의 일이 " + observation.toMillis()
                        + "ms 안에 끝났다: 막혀 있기를 기대했다"
                        + (workFailure == null ? "" : " (일이 던진 예외: " + workFailure + ")"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리다 인터럽트되었다", e);
        }
    }

    /** 트랜잭션을 커밋하고 끝날 때까지 기다린다. 커밋이 실패하면 그 예외를 던진다. */
    public void commit() {
        end(false);
    }

    /** 트랜잭션을 롤백하고 끝날 때까지 기다린다. */
    public void rollback() {
        end(true);
    }

    /**
     * 아직 열려 있으면 롤백을 지시한다. 일이 다른 트랜잭션의 잠금에 막혀 있을 수 있으므로(그 트랜잭션은 뒤에 닫힌다)
     * 일이 끝난 경우에만 트랜잭션이 끝나기를 기다린다.
     */
    @Override
    public void close() {
        if (transactionDone.getCount() == 0) {
            return;
        }
        rollingBack = true;
        finish.countDown();
        if (workDone.getCount() == 0) {
            awaitQuietly(transactionDone);
        }
    }

    private void run(Transactions transactions, Supplier<T> work) {
        try {
            transactions.inTransaction(() -> {
                try {
                    result = work.get();
                } catch (RuntimeException | Error e) {
                    workFailure = e;
                    throw e;
                } finally {
                    workDone.countDown();
                }
                // 상한 안에 지시가 없으면 롤백한다. 실패한 테스트가 다음 테스트에 행을 남기지 않게
                if (!awaitQuietly(finish)) {
                    rollingBack = true;
                }
                if (rollingBack) {
                    throw new RollbackSignal();
                }
                return null;
            });
        } catch (RollbackSignal expected) {
            // 롤백을 지시받았다
        } catch (Throwable e) {
            if (workFailure == null) {
                endFailure = e;
            }
        } finally {
            workDone.countDown();
            transactionDone.countDown();
        }
    }

    private void end(boolean rollback) {
        rollingBack = rollback;
        finish.countDown();
        await(transactionDone, "다른 트랜잭션이");
        if (endFailure != null) {
            throw asUnchecked(endFailure);
        }
    }

    private void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(what + " " + limit.toMillis() + "ms 안에 끝나지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리다 인터럽트되었다", e);
        }
    }

    /** @return 상한 안에 latch가 열렸는지 */
    private boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static RuntimeException asUnchecked(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    /** 롤백을 일으키려고 트랜잭션 안에서 던지는 신호. 밖으로 나가지 않는다. */
    private static final class RollbackSignal extends RuntimeException {
        RollbackSignal() {
            super(null, null, false, false);
        }
    }
}
