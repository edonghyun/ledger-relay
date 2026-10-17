package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.spi.Transactions;
import java.util.function.Supplier;
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

    public JdbcTransactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }

    @Override
    public void requireActive() {
        if (!isActive()) {
            throw new IllegalTransactionStateException(
                    "트랜잭션 밖에서는 명령을 접수할 수 없습니다(G1). 앱 트랜잭션 안에서 submit()을 부르세요");
        }
    }

    @Override
    public boolean isActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
