package io.github.ledgerrelay.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigerbeetle.Client;
import com.tigerbeetle.UInt128;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("기동 준비 — 앱 둘이 같은 DB에 떠도 스키마는 한 번, 계좌는 그대로")
class ShopBootstrapTest {

    final ShopTestEnvironment environment = ShopTestEnvironment.shared();

    @Nested
    @Order(1)
    @DisplayName("빈 DB에서")
    class OnAnEmptyDatabase {

        @Test
        @DisplayName("앱 둘이 동시에 뜨면 스키마는 한 번만 적용되고, 둘 다 성공한다")
        void twoInstancesStartingTogetherApplyTheSchemaOnce() throws Exception {
            try (HikariDataSource database = environment.newEmptyDatabase()) {
                // given
                CountDownLatch together = new CountDownLatch(1);
                List<CompletableFuture<Void>> instances = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    instances.add(CompletableFuture.runAsync(() -> {
                        awaitQuietly(together);
                        ShopSchema.apply(database);
                    }));
                }

                // when
                together.countDown();
                CompletableFuture.allOf(instances.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

                // then
                assertTablesExist(database, "ledger_command", "ledger_ordering_key", "shop_order", "shop_order_command",
                        "shop_instance");
            }
        }

        @Test
        @DisplayName("이미 스키마가 있는 DB에 다시 뜨면 아무것도 바꾸지 않는다")
        void startingAgainChangesNothing() {
            try (HikariDataSource database = environment.newEmptyDatabase()) {
                // given
                ShopSchema.apply(database);
                JdbcClient.create(database).sql("INSERT INTO shop_instance VALUES ('kept', now(), 7)").update();

                // when
                ShopSchema.apply(database);

                // then
                assertThat(JdbcClient.create(database).sql("SELECT submitted_total FROM shop_instance WHERE name = 'kept'")
                        .query(Long.class).single()).as("남아 있는 행").isEqualTo(7L);
            }
        }
    }

    @Nested
    @Order(2)
    @DisplayName("원장 계좌를 준비하면")
    class PreparingAccounts {

        @Test
        @DisplayName("두 번 준비해도 구매자 충전은 ฿1,000,000.00 한 번이다 — 다시 기동해도 두 번 충전하지 않는다")
        void ensuringAccountsTwiceTopsUpOnce() {
            try (Client client = environment.newLedgerClient()) {
                // when
                Accounts.ensure(client);
                Accounts.ensure(client);

                // then: 구매자에게 들어오는 돈은 충전뿐이다. 결제는 나가는 쪽(debit)만 움직인다
                for (long buyer : Accounts.BUYERS) {
                    assertThat(Accounts.balanceOf(client, buyer).creditsPosted()).as("구매자 %d에게 충전된 금액", buyer)
                            .isEqualTo(100_000_000L);
                }
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("TB 주소를 정하면")
    class ResolvingTheLedgerAddress {

        @Test
        @DisplayName("호스트 이름은 IPv4로 바뀐다 — TB 클라이언트는 이름을 풀지 않는다")
        void aHostNameBecomesAnIpv4Address() {
            assertThat(TigerBeetleClientConfig.toIpv4Address("localhost:3001")).isEqualTo("127.0.0.1:3001");
        }

        @Test
        @DisplayName("TB 클라이언트에 호스트 이름을 그대로 주면 만들 때 거부된다 — 그래서 앱이 IPv4로 바꾼다 (F39)")
        void theClientRefusesAHostName() {
            assertThatThrownBy(() -> new Client(UInt128.asBytes(0), new String[] {"localhost:3000"}).close())
                    .as("호스트 이름 주소").hasMessageContaining("Replica addresses format is invalid");
        }

        @Test
        @DisplayName("이미 IPv4면 그대로다")
        void anIpv4AddressIsKept() {
            assertThat(TigerBeetleClientConfig.toIpv4Address("10.0.0.7:3000")).isEqualTo("10.0.0.7:3000");
        }
    }

    private static void assertTablesExist(DataSource database, String... tables) {
        for (String table : tables) {
            assertThat(JdbcClient.create(database).sql("SELECT to_regclass(?)::text").param(table).query(String.class)
                    .optional()).as(table + " 표").contains(table);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
