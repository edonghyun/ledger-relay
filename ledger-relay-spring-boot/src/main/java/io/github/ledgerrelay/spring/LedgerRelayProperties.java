package io.github.ledgerrelay.spring;

import java.math.BigInteger;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code ledger-relay.*} settings. */
@ConfigurationProperties("ledger-relay")
public record LedgerRelayProperties(
        @DefaultValue Tigerbeetle tigerbeetle,
        @DefaultValue Worker worker,
        @DefaultValue Backoff backoff,
        @DefaultValue Metrics metrics) {

    /**
     * @param clusterId TB cluster id
     * @param addresses TB replica addresses, comma-separated. Not used when the app provides a
     *                  {@code com.tigerbeetle.Client} bean
     */
    public record Tigerbeetle(@DefaultValue("0") BigInteger clusterId, @DefaultValue("3000") String addresses) {
    }

    /**
     * @param enabled     when off, there is no worker; the process only accepts and queries commands
     * @param concurrency number of threads in this process that run cycles
     * @param idleDelay   pause after a cycle that claimed no commands
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("4") int concurrency,
            @DefaultValue("128") int batchSize,
            @DefaultValue("1024") int maxInFlight,
            @DefaultValue("30s") Duration leaseDuration,
            @DefaultValue("5s") Duration submitTimeout,
            @DefaultValue("1s") Duration pollTimeout,
            @DefaultValue("100ms") Duration idleDelay) {
    }

    public record Backoff(@DefaultValue("200ms") Duration initial, @DefaultValue("30s") Duration max) {
    }

    /**
     * @param backlogInterval how often the backlog gauges are refreshed from the DB. Only used when the app has a
     *                        Micrometer {@code MeterRegistry}
     */
    public record Metrics(@DefaultValue("5s") Duration backlogInterval) {
    }
}
