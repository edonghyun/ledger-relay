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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Startup preparation — two apps on one DB apply the schema once and keep the accounts")
class ShopBootstrapTest {

    final ShopTestEnvironment environment = ShopTestEnvironment.shared();

    @Nested
    @Order(1)
    @DisplayName("On an empty database")
    class OnAnEmptyDatabase {

        @Test
        @DisplayName("Two apps starting at the same time apply the schema once and both succeed")
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
        @DisplayName("Starting again on a database that has the schema changes nothing")
        void startingAgainChangesNothing() {
            try (HikariDataSource database = environment.newEmptyDatabase()) {
                // given
                ShopSchema.apply(database);
                JdbcClient.create(database).sql("INSERT INTO shop_instance VALUES ('kept', now(), 7)").update();

                // when
                ShopSchema.apply(database);

                // then
                assertInstanceRowKept(database, "kept", 7);
            }
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When the ledger accounts are prepared")
    class PreparingAccounts {

        @Test
        @DisplayName("Preparing twice tops up each buyer with ฿1,000,000.00 only once — a restart never tops up twice")
        void ensuringAccountsTwiceTopsUpOnce() {
            try (Client client = environment.newLedgerClient()) {
                // when
                Accounts.ensure(client);
                Accounts.ensure(client);

                // then: the only money credited to a buyer is the top-up. Payments move only the debit side
                for (long buyer : Accounts.BUYERS) {
                    assertToppedUpWith(client, buyer, 100_000_000L);
                }
            }
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When the TB address is resolved")
    class ResolvingTheLedgerAddress {

        @Test
        @DisplayName("A host name becomes an IPv4 address — the TB client does not resolve names")
        void aHostNameBecomesAnIpv4Address() {
            // when
            String address = TigerBeetleClientConfig.toIpv4Address("localhost:3001");

            // then
            assertThat(address).isEqualTo("127.0.0.1:3001");
        }

        @Test
        @DisplayName("The TB client rejects a host name at construction — which is why the app converts it to IPv4 (F39)")
        void theClientRefusesAHostName() {
            // when
            ThrowingCallable construction = () -> new Client(UInt128.asBytes(0), new String[] {"localhost:3000"}).close();

            // then
            assertRefusedAsInvalidAddress(construction);
        }

        @Test
        @DisplayName("An IPv4 address is kept as is")
        void anIpv4AddressIsKept() {
            // when
            String address = TigerBeetleClientConfig.toIpv4Address("10.0.0.7:3000");

            // then
            assertThat(address).isEqualTo("10.0.0.7:3000");
        }
    }

    private static void assertRefusedAsInvalidAddress(ThrowingCallable construction) {
        assertThatThrownBy(construction).hasMessageContaining("Replica addresses format is invalid");
    }

    private static void assertInstanceRowKept(DataSource database, String name, long submittedTotal) {
        assertThat(JdbcClient.create(database).sql("SELECT submitted_total FROM shop_instance WHERE name = ?").param(name)
                .query(Long.class).single()).as("submitted_total of %s", name).isEqualTo(submittedTotal);
    }

    private static void assertToppedUpWith(Client client, long buyer, long amount) {
        assertThat(Accounts.balanceOf(client, buyer).creditsPosted()).as("amount topped up to buyer %d", buyer)
                .isEqualTo(amount);
    }

    private static void assertTablesExist(DataSource database, String... tables) {
        for (String table : tables) {
            assertThat(JdbcClient.create(database).sql("SELECT to_regclass(?)::text").param(table).query(String.class)
                    .optional()).as(table + " table").contains(table);
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
