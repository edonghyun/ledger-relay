package io.github.ledgerrelay.shop.status;

import io.github.ledgerrelay.shop.ShopProperties;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every second, records in the DB that this instance is alive and how many commands it sent to TB. This makes instances
 * that do not serve the UI visible too.
 */
@Component
class InstanceHeartbeat {

    /** An instance silent for longer than this is dropped from the list. */
    static final int ALIVE_SECONDS = 5;

    record Instance(String name, long submitted) {
    }

    private final JdbcClient sql;
    private final String name;
    private final CountingWorkerEvents events;

    InstanceHeartbeat(DataSource dataSource, ShopProperties properties, CountingWorkerEvents events) {
        this.sql = JdbcClient.create(dataSource);
        this.name = properties.instanceName();
        this.events = events;
    }

    @Scheduled(fixedDelay = 1000)
    void beat() {
        sql.sql("INSERT INTO shop_instance (name, seen_at, submitted_total) VALUES (?, now(), ?) "
                + "ON CONFLICT (name) DO UPDATE SET seen_at = now(), submitted_total = EXCLUDED.submitted_total")
                .params(name, events.submitted()).update();
    }

    List<Instance> alive() {
        return sql.sql("SELECT name, submitted_total FROM shop_instance WHERE seen_at > now() - make_interval(secs => ?) "
                + "ORDER BY name").param(ALIVE_SECONDS)
                .query((row, n) -> new Instance(row.getString("name"), row.getLong("submitted_total"))).list();
    }
}
