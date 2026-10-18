package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.UInt128;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.jdbc.testing.PostgresTestDatabase;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 실제 PostgreSQL과 TigerBeetle 위에서 자동 설정한 릴레이가 이체 하나를 끝까지 처리한다(3b 완료 증거). */
@DisplayName("Spring Boot로 띄운 릴레이 — PG와 TB 위에서 이체 하나가 끝까지 간다")
class SpringBootRelayIntegrationTest {

    static final PostgreSQLContainer PG = PostgresTestDatabase.container();
    static final TigerBeetleContainer TB = new TigerBeetleContainer();
    static final Duration LEASE = Duration.ofSeconds(4);

    static HikariDataSource dataSource;
    static TigerBeetleTestLedger ledger;

    @BeforeAll
    static void start() {
        PG.start();
        TB.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(PG.getJdbcUrl());
        dataSource.setUsername(PG.getUsername());
        dataSource.setPassword(PG.getPassword());
        PostgresTestDatabase.applySchema(dataSource);
        ledger = new TigerBeetleTestLedger(TB.newClient());
    }

    @AfterAll
    static void stop() {
        if (ledger != null) {
            ledger.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        TB.stop();
        PG.stop();
    }

    @Test
    @DisplayName("앱 트랜잭션에서 접수한 이체가 워커를 거쳐 적용됨으로 끝나고, 앱을 끄는 데 lease보다 오래 걸리지 않는다")
    void aSubmittedTransferIsAppliedAndShutdownIsPrompt() {
        // given
        Account payer = ledger.openAccount("구매자");
        Account payee = ledger.openAccount("가맹점");
        RecordingHandler handler = new RecordingHandler();
        ApplicationContextRunner app = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LedgerRelayAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(LedgerOutcomeHandler.class, () -> handler)
                .withPropertyValues("ledger-relay.tigerbeetle.addresses=" + TB.replicaAddress(),
                        "ledger-relay.worker.concurrency=2", "ledger-relay.worker.submit-timeout=1s",
                        "ledger-relay.worker.lease-duration=" + LEASE.toSeconds() + "s");

        app.run(context -> {
            // when
            TransactionTemplate appTransaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            appTransaction.executeWithoutResult(status -> context.getBean(LedgerRelay.class).submit(
                    LedgerCommand.transfer("pay-1").orderingKey("order-1").debit(UInt128.asBigInteger(payer.id()))
                            .credit(UInt128.asBigInteger(payee.id())).amount(500).ledger(1).code(1).build()));
            List<CommandStatus> outcomes = awaitOutcome(handler, "pay-1", Duration.ofSeconds(10));
            long closing = System.nanoTime();
            context.close();
            Duration shutdown = Duration.ofNanos(System.nanoTime() - closing);

            // then
            assertThat(outcomes).as("pay-1의 핸들러 결과").singleElement()
                    .satisfies(outcome -> assertThat(outcome.state()).as("상태").isEqualTo(CommandStatus.State.APPLIED));
            assertThat(shutdown).as("앱을 끄는 데 걸린 시간").isLessThan(LEASE);
        });
    }

    private static List<CommandStatus> awaitOutcome(RecordingHandler handler, String commandId, Duration limit)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            List<CommandStatus> outcomes = handler.completedFor(commandId);
            if (!outcomes.isEmpty()) {
                return outcomes;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(commandId + "의 결과가 " + limit + " 안에 오지 않았다");
    }
}
