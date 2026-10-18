package io.github.ledgerrelay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import io.github.ledgerrelay.core.LedgerRelay;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.application.InFlightLimit;
import io.github.ledgerrelay.core.testing.RecordingHandler;
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
 * 자동 설정이 무엇을 만들고 언제 기동을 막는지(03b). DB와 TB에는 연결하지 않는다: 빈을 만드는 것만으로는 연결하지 않고,
 * 워커는 끈다(켜는 장은 연결 실패를 로그로 남기며 돈다).
 */
@DisplayName("자동 설정 — 의존성 하나와 ledger-relay.* 설정으로 릴레이를 쓴다 (03b)")
class LedgerRelayAutoConfigurationTest {

    /** 연결할 수 없는 주소다. 빈을 만드는 데에는 연결이 필요 없다. */
    final DataSource dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none");

    final ApplicationContextRunner app = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LedgerRelayAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withPropertyValues("ledger-relay.tigerbeetle.addresses=127.0.0.1:1", "ledger-relay.worker.enabled=false");

    @Nested
    @Order(1)
    @DisplayName("앱이 DataSource와 핸들러를 주면")
    class WithTheAppsBeans {

        @Test
        @DisplayName("접수 포트와 운영자 포트가 하나씩 생긴다")
        void theRelayAndOperatorAreProvided() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).run(context -> {
                assertThat(context).hasSingleBean(LedgerRelay.class);
                assertThat(context).hasSingleBean(LedgerRelayOperator.class);
            });
        }

        @Test
        @DisplayName("앱이 TB 클라이언트를 주면 그것을 쓰고 새로 만들지 않는다")
        void anAppClientIsUsedInsteadOfOurs() {
            Client appClient = new Client(UInt128.asBytes(0), new String[] {"127.0.0.1:1"});
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).withBean(Client.class, () -> appClient)
                    .run(context -> assertThat(context.getBean(Client.class)).as("쓰는 TB 클라이언트").isSameAs(appClient));
            appClient.close();
        }

        @Test
        @DisplayName("워커를 끄면 워커 루프가 없다 — 접수·조회만 하는 앱")
        void disabledWorkerStartsNoThreads() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new)
                    .run(context -> assertThat(context).doesNotHaveBean(RelayWorkerLoop.class));
        }

        @Test
        @DisplayName("in-flight 슬롯 수는 설정의 max-in-flight다 (02d I5)")
        void slotsFollowMaxInFlight() {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new)
                    .withPropertyValues("ledger-relay.worker.enabled=true", "ledger-relay.worker.max-in-flight=512")
                    .run(context -> assertThat(context.getBean(InFlightLimit.class).capacity()).as("슬롯 수")
                            .isEqualTo(512));
        }
    }

    @Nested
    @Order(2)
    @DisplayName("기동을 막는 경우")
    class StartupFailures {

        @Test
        @DisplayName("핸들러 빈이 없으면 무엇이 필요한지 말하며 실패한다")
        void withoutAHandlerStartupFails() {
            app.run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).as("기동 실패").rootCause()
                        .hasMessageContaining("LedgerOutcomeHandler 빈이 필요합니다");
            });
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "lease가 submit-timeout의 두 배보다 짧다 | ledger-relay.worker.lease-duration=5s | 5S",
                "batch-size가 TB 한 요청의 상한(8189)을 넘는다 | ledger-relay.worker.batch-size=9000 | 9000",
                "max-in-flight가 batch-size보다 작다 | ledger-relay.worker.max-in-flight=64 | 64"})
        @DisplayName("워커 설정이 어긋나면 어긋난 값을 말하며 실패한다 — 돌다가 깨지지 않게")
        void invalidWorkerSettingsFailStartup(String situation, String property, String offendingValue) {
            app.withBean(LedgerOutcomeHandler.class, RecordingHandler::new).withPropertyValues(property).run(context -> {
                assertThat(context).as(situation).hasFailed();
                assertThat(context.getStartupFailure()).as("기동 실패").rootCause().hasMessageContaining(offendingValue);
            });
        }
    }
}
