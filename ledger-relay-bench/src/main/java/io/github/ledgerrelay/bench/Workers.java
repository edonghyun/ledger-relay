package io.github.ledgerrelay.bench;

import com.tigerbeetle.Client;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.CycleReport;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.github.ledgerrelay.core.application.RelayWorker;
import io.github.ledgerrelay.core.application.WorkerSettings;
import io.github.ledgerrelay.jdbc.JdbcCommandStore;
import io.github.ledgerrelay.jdbc.JdbcOrderingKeys;
import io.github.ledgerrelay.jdbc.JdbcTransactions;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleLedgerClient;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleTransferIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The workers of one run. Each worker is assembled like one app instance: its own thread, in-flight slots, TB client
 * and connection pool. They share only the database and TigerBeetle.
 */
final class Workers implements AutoCloseable {

    static final int POOL_PER_WORKER = 4;
    /** How long a worker rests after a cycle that found nothing to do, like the Spring worker loop's idle delay. */
    static final Duration IDLE_DELAY = Duration.ofMillis(5);

    private final List<Worker> workers;
    private boolean closed;

    private Workers(List<Worker> workers) {
        this.workers = workers;
    }

    static Workers start(BenchEnvironment.Run run, int count, int batchSize, Recorder recorder) {
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            workers.add(new Worker(run.environment(), "bench-worker-" + i, batchSize, recorder));
        }
        workers.forEach(Worker::start);
        return new Workers(workers);
    }

    @Override
    public void close() throws InterruptedException {
        if (closed) {
            return;
        }
        closed = true;
        for (Worker worker : workers) {
            worker.stop();
        }
    }

    private static final class Worker {

        private final HikariDataSource pool;
        private final Client client;
        private final RelayWorker relayWorker;
        private final Recorder recorder;
        private final Thread thread;
        private volatile boolean running = true;

        Worker(BenchEnvironment environment, String name, int batchSize, Recorder recorder) {
            this.pool = environment.pool(POOL_PER_WORKER, name);
            this.client = environment.newClient();
            this.recorder = recorder;
            JdbcTransactions transactions = new JdbcTransactions(new DataSourceTransactionManager(pool), pool);
            CommandLifecycle lifecycle = new CommandLifecycle(new JdbcOrderingKeys(pool), new JdbcCommandStore(pool),
                    transactions, new TigerBeetleTransferIds(), BenchEnvironment.NO_OP,
                    new Backoff(Duration.ofMillis(200), Duration.ofSeconds(2)));
            int maxInFlight = Math.max(batchSize * 8, 1024);
            WorkerSettings settings = new WorkerSettings(batchSize, maxInFlight, Duration.ofSeconds(30),
                    Duration.ofSeconds(5), Duration.ofMillis(50));
            this.relayWorker = new RelayWorker(lifecycle, new TigerBeetleLedgerClient(client),
                    new InFlightLimit(maxInFlight), settings, recorder);
            this.thread = new Thread(this::loop, name);
        }

        void start() {
            thread.start();
        }

        private void loop() {
            while (running) {
                CycleReport report;
                try {
                    report = relayWorker.runOnce();
                } catch (RuntimeException e) {
                    // A store error ends only this cycle; the lease brings the commands back
                    recorder.writeFailures.increment();
                    rest(Duration.ofMillis(50));
                    continue;
                }
                recorder.cycles.increment();
                if (report.claimed() == 0) {
                    recorder.idleCycles.increment();
                    rest(IDLE_DELAY);
                }
            }
        }

        private static void rest(Duration duration) {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join();
            client.close();
            pool.close();
        }
    }
}
