package io.github.ledgerrelay.spring;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.github.ledgerrelay.core.application.RelayOperator;
import io.github.ledgerrelay.core.application.RelayWorker;
import io.github.ledgerrelay.core.application.WorkerSettings;
import io.github.ledgerrelay.core.spi.CommandStore;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.OrderingKeys;
import io.github.ledgerrelay.core.spi.Transactions;
import io.github.ledgerrelay.core.spi.TransferIdGenerator;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.jdbc.JdbcCommandStore;
import io.github.ledgerrelay.jdbc.JdbcOrderingKeys;
import io.github.ledgerrelay.jdbc.JdbcTransactions;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleLedgerClient;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleTransferIds;
import java.time.Duration;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ledger-relay auto-configuration. The app provides {@code DataSource}, {@code PlatformTransactionManager} and
 * {@link LedgerOutcomeHandler} beans. No bean is created when the app already provides one of the same type.
 *
 * <p>The three stores (transactions, ordering keys, commands) receive the same {@code DataSource} object that the
 * transaction manager holds. If it differs, acceptance is not bound to the app's transaction (F18).
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@EnableConfigurationProperties(LedgerRelayProperties.class)
public class LedgerRelayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Transactions ledgerRelayTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new JdbcTransactions(transactionManager, dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    OrderingKeys ledgerRelayOrderingKeys(DataSource dataSource) {
        return new JdbcOrderingKeys(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    CommandStore ledgerRelayCommandStore(DataSource dataSource) {
        return new JdbcCommandStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    Client ledgerRelayTigerBeetleClient(LedgerRelayProperties properties) {
        String[] addresses = Arrays.stream(properties.tigerbeetle().addresses().split(","))
                .map(String::trim).filter(address -> !address.isEmpty()).toArray(String[]::new);
        return new Client(UInt128.asBytes(properties.tigerbeetle().clusterId()), addresses);
    }

    @Bean
    @ConditionalOnMissingBean
    LedgerClient ledgerRelayLedgerClient(Client client) {
        return new TigerBeetleLedgerClient(client);
    }

    @Bean
    @ConditionalOnMissingBean
    TransferIdGenerator ledgerRelayTransferIds() {
        return new TigerBeetleTransferIds();
    }

    @Bean
    @ConditionalOnMissingBean
    Backoff ledgerRelayBackoff(LedgerRelayProperties properties) {
        return new Backoff(properties.backoff().initial(), properties.backoff().max());
    }

    /** Created even when the worker is off. Bad settings must surface at startup, not break the worker later. */
    @Bean
    @ConditionalOnMissingBean
    WorkerSettings ledgerRelayWorkerSettings(LedgerRelayProperties properties) {
        LedgerRelayProperties.Worker worker = properties.worker();
        return new WorkerSettings(worker.batchSize(), worker.maxInFlight(), worker.leaseDuration(),
                worker.submitTimeout(), worker.pollTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    CommandLifecycle ledgerRelay(OrderingKeys keys, CommandStore store, Transactions transactions,
            TransferIdGenerator transferIds, ObjectProvider<LedgerOutcomeHandler> handlers, Backoff backoff) {
        LedgerOutcomeHandler handler = handlers.getIfAvailable();
        if (handler == null) {
            throw new IllegalStateException("ledger-relay: a LedgerOutcomeHandler bean is required. Register as a bean "
                    + "the handler that applies the result to the app's data when a command finishes");
        }
        return new CommandLifecycle(keys, store, transactions, transferIds, handler, backoff);
    }

    @Bean
    @ConditionalOnMissingBean
    LedgerRelayOperator ledgerRelayOperator(CommandStore store, Transactions transactions, CommandLifecycle lifecycle,
            LedgerClient ledger, WorkerSettings settings) {
        return new RelayOperator(store, transactions, lifecycle, ledger, settings.submitTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerEvents ledgerRelayWorkerEvents() {
        return new LoggingWorkerEvents();
    }

    /** Turns on only when the app uses health (actuator). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class HealthContributor {

        @Bean
        @ConditionalOnMissingBean
        LedgerRelayHealthIndicator ledgerRelayHealthIndicator(CommandStore store, Transactions transactions,
                LedgerClient ledger) {
            return new LedgerRelayHealthIndicator(store, transactions, ledger, Duration.ofSeconds(5));
        }
    }

    /** Workers: the slots are created from the same maxInFlight, so the setting and the actual limit cannot diverge. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ledger-relay.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Workers {

        @Bean
        @ConditionalOnMissingBean
        InFlightLimit ledgerRelayInFlightLimit(WorkerSettings settings) {
            return new InFlightLimit(settings.maxInFlight());
        }

        @Bean
        @ConditionalOnMissingBean
        RelayWorker ledgerRelayWorker(CommandLifecycle lifecycle, LedgerClient ledger, InFlightLimit slots,
                WorkerSettings settings, WorkerEvents events) {
            return new RelayWorker(lifecycle, ledger, slots, settings, events);
        }

        @Bean
        @ConditionalOnMissingBean
        RelayWorkerLoop ledgerRelayWorkerLoop(RelayWorker worker, WorkerSettings settings,
                LedgerRelayProperties properties) {
            return new RelayWorkerLoop(worker::runOnce, properties.worker().concurrency(),
                    properties.worker().idleDelay(), properties.backoff().initial(), settings.lease());
        }
    }
}
