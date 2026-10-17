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
 * Spring 트랜잭션 매니저 위의 트랜잭션 포트(P2b-12). 앱이 쓰는 매니저(DataSource, JPA)를 그대로 받으므로 접수는 앱
 * 트랜잭션에 합류한다(R16). 이미 열린 트랜잭션이 있으면 합류하고(PROPAGATION_REQUIRED), 없으면 새로 연다.
 *
 * <p>새로 여는 트랜잭션(워커 단계)은 READ COMMITTED로 연다(P2b-16). 행 잠금으로 순서를 잡는 설계라, 풀의 기본값이
 * REPEATABLE READ여도 다른 워커와 부딪히면 직렬화 실패로 터지지 않고 기다려야 한다. 합류할 때는 앱의 격리 수준을 그대로
 * 둔다(Spring은 합류 시 격리 수준을 바꾸지 않는다). 높은 수준의 앱 트랜잭션에서는 동시 접수가 직렬화 실패로 끝날 수
 * 있으며 재시도는 앱이 한다(P2b-17).
 */
public final class JdbcTransactions implements Transactions {

    private final TransactionTemplate template;
    private final DataSource dataSource;

    /**
     * @param dataSource 저장소({@link JdbcCommandStore})가 쓰는 것과 같은 DataSource. 접수할 때 이 DataSource의 커넥션이
     *                   트랜잭션에 묶여 있는지 확인한다
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
     * 접수 앞의 확인(G1). 트랜잭션이 열려 있는 것만으로는 부족하다. 그 트랜잭션에 저장소의 DataSource 커넥션이 묶여 있어야
     * 접수가 앱 트랜잭션과 함께 커밋·롤백된다. 묶여 있지 않으면(JDBC 커넥션을 노출하지 않는 JPA dialect, 트랜잭션 매니저와
     * 저장소가 서로 다른 DataSource 객체를 쥔 경우) {@code JdbcClient}는 별도 커넥션으로 auto-commit한다. 그래서 거부한다(F18).
     */
    @Override
    public void requireActive() {
        if (!isActive()) {
            throw new IllegalTransactionStateException(
                    "트랜잭션 밖에서는 명령을 접수할 수 없습니다(G1). 앱 트랜잭션 안에서 submit()을 부르세요");
        }
        if (TransactionSynchronizationManager.getResource(dataSource) == null) {
            throw new IllegalTransactionStateException("열린 트랜잭션에 저장소의 DataSource 커넥션이 묶여 있지 않습니다(G1). "
                    + "트랜잭션 매니저와 JdbcCommandStore에 같은 DataSource를 주고, JPA라면 JDBC 커넥션을 노출하는 "
                    + "dialect(HibernateJpaDialect)를 쓰세요");
        }
    }

    @Override
    public boolean isActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
