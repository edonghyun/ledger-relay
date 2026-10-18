package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import io.github.ledgerrelay.core.testing.RecordingHandler;
import io.github.ledgerrelay.core.testing.RecordingWorkerEvents;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the auto-configuration creates and when it blocks startup. It connects to neither the DB nor TB: creating the
 * beans does not connect, and the worker is off (a running worker keeps logging connection failures).
 */
@DisplayName("Auto-configuration — one dependency and ledger-relay.* settings are enough to use the relay")
class LedgerRelayAutoConfigurationTest {

    /** An address that cannot be reached. Creating the beans needs no connection. */
    final DataSource dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none");

    final ApplicationContextRunner app = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LedgerRelayAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withPropertyValues("ledger-relay.tigerbeetle.addresses=127.0.0.1:1", "ledger-relay.worker.enabled=false");

    @Nested
    @Order(1)
    @DisplayName("When the app provides a DataSource and a handler")
    class WithTheAppsBeans {

        @Test
        @DisplayName("Exactly one accept port and one operator port are created")
        void theRelayAndOperatorAreProvided() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).run(context -> {
                assertThat(context).hasSingleBean(LedgerRelay.class);
                assertThat(context).hasSingleBean(LedgerRelayOperator.class);
            });
        }

        @Test
        @DisplayName("When the app provides a TB client, that client is used and no new one is created")
        void anAppClientIsUsedInsteadOfOurs() {
            Client appClient = new Client(UInt128.asBytes(0), new String[] {"127.0.0.1:1"});
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).withBean(Client.class, () -> appClient)
                    .run(context -> assertThat(context.getBean(Client.class)).as("TB client in use")
                            .isSameAs(appClient));
            appClient.close();
        }

        @Test
        @DisplayName("With the worker off there is no worker loop — an app that only accepts and queries")
        void disabledWorkerStartsNoThreads() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new)
                    .run(context -> assertThat(context).doesNotHaveBean(RelayWorkerLoop.class));
        }

        @Test
        @DisplayName("The number of in-flight slots is the max-in-flight setting")
        void slotsFollowMaxInFlight() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new)
                    .withPropertyValues("ledger-relay.worker.enabled=true", "ledger-relay.worker.max-in-flight=512")
                    .run(context -> assertThat(context.getBean(InFlightLimit.class).capacity()).as("slot count")
                            .isEqualTo(512));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When startup is blocked")
    class StartupFailures {

        @Test
        @DisplayName("Without a handler bean, startup fails and the message says what is needed")
        void withoutAHandlerStartupFails() {
            app.run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).as("startup failure").rootCause()
                        .hasMessageContaining("a LedgerOutcomeHandler bean is required");
            });
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "lease is shorter than twice submit-timeout | ledger-relay.worker.lease-duration=5s | 5S",
                "batch-size exceeds the limit of one TB request (8189) | ledger-relay.worker.batch-size=9000 | 9000",
                "max-in-flight is smaller than batch-size | ledger-relay.worker.max-in-flight=64 | 64"})
        @DisplayName("Inconsistent worker settings fail startup naming the offending value — not breaking later while running")
        void invalidWorkerSettingsFailStartup(String situation, String property, String offendingValue) {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).withPropertyValues(property).run(context -> {
                assertThat(context).as(situation).hasFailed();
                assertThat(context.getStartupFailure()).as("startup failure").rootCause()
                        .hasMessageContaining(offendingValue);
            });
        }
    }

    @Nested
    @Order(3)
    @DisplayName("Metrics and worker events")
    class MetricsAndEvents {

        @Test
        @DisplayName("When the app has a MeterRegistry, metrics and the backlog sampler are created")
        void withARegistryMetricsAreRegistered() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(context -> {
                        assertThat(context).hasSingleBean(MicrometerWorkerEvents.class);
                        assertThat(context).hasSingleBean(BacklogSampler.class);
                    });
        }

        @Test
        @DisplayName("Without a MeterRegistry only logging is wired, and the app still starts")
        void withoutARegistryOnlyLoggingIsWired() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LoggingWorkerEvents.class);
                assertThat(context).doesNotHaveBean(MicrometerWorkerEvents.class);
                assertThat(context).doesNotHaveBean(BacklogSampler.class);
            });
        }

        @Test
        @DisplayName("An app's own WorkerEvents bean is added next to logging, not substituted for it")
        void anAppListenerIsAddedNotSubstituted() {
            RecordingWorkerEvents appEvents = new RecordingWorkerEvents();
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new)
                    .withBean(RecordingWorkerEvents.class, () -> appEvents)
                    .run(context -> assertThat(context.getBeansOfType(WorkerEvents.class).values())
                            .as("worker event listeners").hasSize(2).contains(appEvents)
                            .hasAtLeastOneElementOfType(LoggingWorkerEvents.class));
        }
    }
}
