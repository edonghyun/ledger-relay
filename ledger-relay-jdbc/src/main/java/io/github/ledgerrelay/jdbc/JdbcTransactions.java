package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.spi.Transactions;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction port on top of a Spring transaction manager. It takes the manager the app uses (DataSource, JPA) as is,
 * so acceptance joins the app transaction. If a transaction is already open it joins it (PROPAGATION_REQUIRED);
 * otherwise it opens a new one.
 *
 * <p>Transactions it opens itself (worker steps) run at READ COMMITTED. The design orders work with row locks, so even
 * if the pool default is REPEATABLE READ, a clash with another worker must wait instead of failing with a
 * serialization failure. When joining, the app's isolation level stays as it is (Spring does not change the isolation
 * level when joining). In an app transaction at a higher level, concurrent acceptance can end in a serialization
 * failure, and the app does the retry.
 *
 * <p>JTA ({@code JtaTransactionManager}) is not supported. The DataSource connection is not bound when the transaction
 * starts, so {@link #requireActive()} rejects acceptance. JPA's {@code DefaultJpaDialect} rejects setting an isolation
 * level, so worker transactions cannot start (docs/development.md).
 */
public final class JdbcTransactions implements Transactions {

    private final TransactionTemplate template;
    private final DataSource dataSource;

    /**
     * @param dataSource the same DataSource the store ({@link JdbcCommandStore}) uses. On acceptance it checks that a
     *                   connection of this DataSource is bound to the transaction
     */
    public JdbcTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        this.dataSource = dataSource;
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }

    /**
     * Check before acceptance (G1). An open transaction is not enough. A connection of the store's DataSource must be
     * bound to that transaction, so that acceptance commits and rolls back with the app transaction. If it is not bound
     * (a JPA dialect that does not expose the JDBC connection, or a transaction manager and store that hold different
     * DataSource objects), {@code JdbcClient} auto-commits on a separate connection. So it is rejected (F18).
     */
    @Override
    public void requireActive() {
        if (!isActive()) {
            throw new IllegalTransactionStateException(
                    "Commands cannot be accepted outside a transaction (G1). Call submit() inside an app transaction");
        }
        if (TransactionSynchronizationManager.getResource(dataSource) == null) {
            throw new IllegalTransactionStateException("The open transaction has no bound connection from the store's "
                    + "DataSource (G1). Give the transaction manager and JdbcCommandStore the same DataSource, "
                    + "and with JPA use a dialect that exposes the JDBC connection (HibernateJpaDialect)");
        }
    }

    @Override
    public boolean isActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
