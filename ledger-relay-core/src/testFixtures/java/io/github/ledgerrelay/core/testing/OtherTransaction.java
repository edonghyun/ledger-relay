package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.spi.Transactions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A transaction opened on another thread. Concurrency sections use it to make "another worker, another app transaction"
 * deterministic.
 * After its work finishes, it keeps the transaction open and waits until {@link #commit()} or {@link #rollback()} is
 * called.
 * So the row locks that transaction took stay alive until the moment the test chooses. With no instruction within the
 * limit (10 seconds), it rolls back.
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

    /** Opens a transaction on a new thread and runs {@code work}. Returns right away. */
    public static <T> OtherTransaction<T> start(Transactions transactions, Supplier<T> work) {
        return start(transactions, work, LIMIT);
    }

    /** Opens with a given limit. This class's own tests use it to check briefly what happens after the limit passes. */
    static <T> OtherTransaction<T> start(Transactions transactions, Supplier<T> work, Duration limit) {
        OtherTransaction<T> other = new OtherTransaction<>(limit);
        Thread thread = new Thread(() -> other.run(transactions, work), "other-transaction");
        thread.setDaemon(true);
        thread.start();
        return other;
    }

    /** Waits for the work to finish and returns its result. If the work threw, throws that exception. */
    public T awaitWork() {
        await(workDone, "The other transaction's work");
        if (workFailure != null) {
            throw asUnchecked(workFailure);
        }
        return result;
    }

    /** Checks that the work did not finish during the observation time — it is blocked on something. */
    public void assertStillWorkingAfter(Duration observation) {
        try {
            if (workDone.await(observation.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("The other transaction's work finished within " + observation.toMillis()
                        + "ms: expected it to be blocked"
                        + (workFailure == null ? "" : " (exception thrown by the work: " + workFailure + ")"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }

    /**
     * Checks that the work finished within the observation time and returns the result — nothing blocked it. If the
     * work threw, throws that exception.
     */
    public T assertFinishedWithin(Duration observation) {
        try {
            if (!workDone.await(observation.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("The other transaction's work did not finish within " + observation.toMillis()
                        + "ms: expected it not to be blocked");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
        return awaitWork();
    }

    /** Commits the transaction and waits until it ends. If the commit fails, throws that exception. */
    public void commit() {
        end(false);
    }

    /** Rolls back the transaction and waits until it ends. */
    public void rollback() {
        end(true);
    }

    /**
     * If it is still open, instructs a rollback. The work may be blocked on another transaction's lock (that
     * transaction
     * closes later), so it waits for the transaction to end only if the work has finished.
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
                // With no instruction within the limit, roll back, so a failed test leaves no rows for the next test
                if (!awaitQuietly(finish)) {
                    rollingBack = true;
                }
                if (rollingBack) {
                    throw new RollbackSignal();
                }
                return null;
            });
        } catch (RollbackSignal expected) {
            // A rollback was instructed
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
        await(transactionDone, "The other transaction");
        if (endFailure != null) {
            throw asUnchecked(endFailure);
        }
    }

    private void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(what + " did not finish within " + limit.toMillis() + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }

    /** @return whether the latch opened within the limit */
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

    /** The signal thrown inside the transaction to cause a rollback. It never leaves the class. */
    private static final class RollbackSignal extends RuntimeException {
        RollbackSignal() {
            super(null, null, false, false);
        }
    }
}
