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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** On real PostgreSQL and TigerBeetle, the auto-configured relay processes one transfer end to end. */
@DisplayName("Relay started with Spring Boot — one transfer goes end to end on PG and TB")
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
    @DisplayName("A transfer accepted in an app transaction ends as applied through the worker, and shutting the app down takes less than the lease")
    void aSubmittedTransferIsAppliedAndShutdownIsPrompt() {
        // given
        Account payer = ledger.openAccount("buyer");
        Account payee = ledger.openAccount("merchant");
        RecordingHandler handler = new RecordingHandler();
        // Closing the context closes a DataSource bean; the pool is shared by the tests, so the app gets a wrapper
        DataSource appDataSource = new DelegatingDataSource(dataSource);
        ApplicationContextRunner app = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LedgerRelayAutoConfiguration.class))
                .withBean(DataSource.class, () -> appDataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(appDataSource))
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
            assertThat(outcomes).as("handler outcomes for pay-1").singleElement()
                    .satisfies(outcome -> assertThat(outcome.state()).as("state").isEqualTo(CommandStatus.State.APPLIED));
            assertThat(shutdown).as("time taken to shut the app down").isLessThan(LEASE);
        });
    }

    @Test
    @DisplayName("After one transfer, the metrics count it: finished applied, its duration, the TB request, and an empty backlog")
    void metricsCountAProcessedTransfer() {
        // given
        Account payer = ledger.openAccount("buyer");
        Account payee = ledger.openAccount("merchant");
        RecordingHandler handler = new RecordingHandler();
        MeterRegistry registry = new SimpleMeterRegistry();
        // Closing the context closes a DataSource bean; the pool is shared by the tests, so the app gets a wrapper
        DataSource appDataSource = new DelegatingDataSource(dataSource);
        ApplicationContextRunner app = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LedgerRelayAutoConfiguration.class))
                .withBean(DataSource.class, () -> appDataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(appDataSource))
                .withBean(LedgerOutcomeHandler.class, () -> handler)
                .withBean(MeterRegistry.class, () -> registry)
                .withPropertyValues("ledger-relay.tigerbeetle.addresses=" + TB.replicaAddress(),
                        "ledger-relay.worker.submit-timeout=1s", "ledger-relay.metrics.backlog-interval=100ms");

        app.run(context -> {
            // when
            TransactionTemplate appTransaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            appTransaction.executeWithoutResult(status -> context.getBean(LedgerRelay.class).submit(
                    LedgerCommand.transfer("pay-metrics").orderingKey("order-metrics")
                            .debit(UInt128.asBigInteger(payer.id())).credit(UInt128.asBigInteger(payee.id()))
                            .amount(500).ledger(1).code(1).build()));
            awaitOutcome(handler, "pay-metrics", Duration.ofSeconds(10));
            awaitPendingGauge(registry, 0, Duration.ofSeconds(5));

            // then
            assertThat(registry.get("ledger.commands.finished").tag("state", "applied").counter().count())
                    .as("finished applied commands").isEqualTo(1);
            assertThat(registry.get("ledger.command.duration").tag("state", "applied").timer().count())
                    .as("recorded accept-to-finish durations").isEqualTo(1);
            assertThat(registry.get("ledger.submit.latency").tag("outcome", "replied").timer().count())
                    .as("answered TB requests").isGreaterThanOrEqualTo(1);
        });
    }

    /** Waits until the sampled pending gauge reads {@code expected}. */
    private static void awaitPendingGauge(MeterRegistry registry, double expected, Duration limit)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (registry.get("ledger.commands.waiting").tag("state", "pending").gauge().value() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The pending gauge did not read " + expected + " within " + limit);
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
        throw new AssertionError("No outcome for " + commandId + " arrived within " + limit);
    }
}
