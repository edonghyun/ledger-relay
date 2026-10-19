package io.github.ledgerrelay.bench;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.core.LedgerCommand;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.jdbc.JdbcCommandStore;
import io.github.ledgerrelay.jdbc.JdbcOrderingKeys;
import io.github.ledgerrelay.jdbc.JdbcTransactions;
import io.github.ledgerrelay.jdbc.testing.PostgresTestDatabase;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleTransferIds;
import io.github.ledgerrelay.tigerbeetle.testing.Account;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleContainer;
import io.github.ledgerrelay.tigerbeetle.testing.TigerBeetleTestLedger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and TigerBeetle for a whole measurement session, started once. Each run starts from empty library tables
 * and a new pair of TB accounts, so runs never see each other's commands or transfers.
 *
 * <p>PostgreSQL runs with its default durability ({@code fsync} on, unlike the test container's default), because
 * commit cost is part of what is measured. TigerBeetle runs without {@code --development}, whose request size and
 * client count limits a batch of 512 and eight workers exceed.
 */
public final class BenchEnvironment implements AutoCloseable {

    static final long AMOUNT = 100;
    static final int ACCEPT_THREADS = 8;
    static final LedgerOutcomeHandler NO_OP = outcome -> {
    };

    private final PostgreSQLContainer postgres;
    private final TigerBeetleContainer tigerbeetle;
    private final HikariDataSource admin;
    private final Client adminClient;
    private final TigerBeetleTestLedger adminLedger;
    private int runs;

    private BenchEnvironment(PostgreSQLContainer postgres, TigerBeetleContainer tigerbeetle) {
        this.postgres = postgres;
        this.tigerbeetle = tigerbeetle;
        this.admin = pool(32, "bench-admin");
        this.adminClient = tigerbeetle.newClient();
        this.adminLedger = new TigerBeetleTestLedger(adminClient);
        PostgresTestDatabase.applySchema(admin);
    }

    public static BenchEnvironment start() {
        PostgreSQLContainer postgres = PostgresTestDatabase.container()
                .withCommand("postgres", "-c", "max_connections=300", "-c", "shared_buffers=256MB");
        TigerBeetleContainer tigerbeetle = TigerBeetleContainer.production();
        postgres.start();
        tigerbeetle.start();
        return new BenchEnvironment(postgres, tigerbeetle);
    }

    /** Empties the library tables and opens a new pair of accounts for the next run. */
    public Run reset() {
        JdbcClient.create(admin).sql("TRUNCATE ledger_command, ledger_ordering_key RESTART IDENTITY").update();
        runs++;
        return new Run(runs, adminLedger.openAccount("debit-" + runs), adminLedger.openAccount("credit-" + runs));
    }

    HikariDataSource pool(int size, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(size);
        config.setPoolName(name);
        return new HikariDataSource(config);
    }

    Client newClient() {
        return tigerbeetle.newClient();
    }

    DataSource admin() {
        return admin;
    }

    String versions() {
        return "PostgreSQL " + postgres.getDockerImageName() + ", TigerBeetle " + tigerbeetle.getDockerImageName();
    }

    @Override
    public void close() {
        adminLedger.close();
        admin.close();
        tigerbeetle.stop();
        postgres.stop();
    }

    /** One run: its accounts, how to accept its commands, and what it left in PG and TB. */
    public final class Run {

        final int number;
        final Account debit;
        final Account credit;
        final JdbcTransactions transactions;
        final CommandLifecycle relay;

        private Run(int number, Account debit, Account credit) {
            this.number = number;
            this.debit = debit;
            this.credit = credit;
            this.transactions = new JdbcTransactions(new DataSourceTransactionManager(admin), admin);
            this.relay = new CommandLifecycle(new JdbcOrderingKeys(admin), new JdbcCommandStore(admin), transactions,
                    new TigerBeetleTransferIds(), NO_OP, new Backoff(Duration.ofMillis(200), Duration.ofSeconds(2)));
        }

        BenchEnvironment environment() {
            return BenchEnvironment.this;
        }

        /** Accepts commands 0..n-1 with {@link #ACCEPT_THREADS} threads, one app transaction each. */
        public void accept(int n, KeySpread keys) throws Exception {
            ExecutorService threads = Executors.newFixedThreadPool(ACCEPT_THREADS);
            try {
                List<Future<Long>> accepted = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int index = i;
                    accepted.add(threads.submit(() -> acceptOne(index, keys)));
                }
                for (Future<Long> one : accepted) {
                    one.get();
                }
            } finally {
                threads.shutdownNow();
            }
        }

        /** Accepts command {@code index} in its own transaction and returns how long that took, in nanoseconds. */
        long acceptOne(int index, KeySpread keys) {
            LedgerCommand command = LedgerCommand.transfer("r" + number + "-" + index).orderingKey(keys.key(index))
                    .debit(UInt128.asBigInteger(debit.id())).credit(UInt128.asBigInteger(credit.id())).amount(AMOUNT)
                    .ledger(TigerBeetleTestLedger.LEDGER).code(TigerBeetleTestLedger.CODE).build();
            long start = System.nanoTime();
            transactions.inTransaction(() -> relay.submit(command));
            return System.nanoTime() - start;
        }

        /** Starts workers, waits until nothing is left to do, and stops them. */
        public void processAll(int workers, int batchSize, Duration limit) throws Exception {
            try (Workers running = Workers.start(this, workers, batchSize, new Recorder())) {
                awaitFinished(limit);
            }
        }

        /** Waits until no command is pending, claimed or halted. Returns false on timeout. */
        boolean awaitFinished(Duration limit) throws InterruptedException {
            long deadline = System.nanoTime() + limit.toNanos();
            while (System.nanoTime() < deadline) {
                if (unfinished() == 0) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }

        long unfinished() {
            return JdbcClient.create(admin)
                    .sql("SELECT count(*) FROM ledger_command WHERE state IN ('PENDING','CLAIMED','HALTED')")
                    .query(Long.class).single();
        }

        /** The database clock, the clock {@code accepted_at} and {@code completed_at} use. */
        Instant dbNow() {
            return JdbcClient.create(admin).sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single()
                    .toInstant();
        }

        Instant lastCompletion() {
            return JdbcClient.create(admin).sql("SELECT max(completed_at) FROM ledger_command")
                    .query(OffsetDateTime.class).single().toInstant();
        }

        /** Transfers TB holds on this run's credit account. Every command of the run credits it once. */
        long transfersInLedger() {
            return adminLedger.balanceOf(credit).creditsPosted() / AMOUNT;
        }

        /** Control experiment: one transfer to the run's credit account that did not go through the relay. */
        public void transferOutsideTheRelay() {
            adminLedger.deposit(credit, AMOUNT);
        }

        public Correctness.Verdict check() {
            return Correctness.check(this);
        }
    }
}
