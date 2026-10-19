package io.github.ledgerrelay.bench;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Samples what active PostgreSQL sessions are waiting on, every 200 ms, while a run is going. */
final class WaitSampler implements AutoCloseable {

    private static final String ACTIVE = """
            SELECT coalesce(wait_event_type || ':' || wait_event, 'CPU') AS waiting
            FROM pg_stat_activity
            WHERE datname = current_database() AND state = 'active' AND pid <> pg_backend_pid()
            """;

    private final JdbcClient sql;
    private final Map<String, Long> samples = new TreeMap<>();
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();

    WaitSampler(DataSource dataSource) {
        this.sql = JdbcClient.create(dataSource);
        thread.scheduleWithFixedDelay(this::sample, 0, 200, TimeUnit.MILLISECONDS);
    }

    private void sample() {
        try {
            for (String waiting : sql.sql(ACTIVE).query(String.class).list()) {
                synchronized (samples) {
                    samples.merge(waiting, 1L, Long::sum);
                }
            }
        } catch (RuntimeException e) {
            // A failed sample only loses one data point of the notes
        }
    }

    Map<String, Long> samples() {
        synchronized (samples) {
            return Map.copyOf(samples);
        }
    }

    @Override
    public void close() throws InterruptedException {
        thread.shutdown();
        thread.awaitTermination(5, TimeUnit.SECONDS);
    }
}
