package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.testing.CommandStoreContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("PostgreSQL storage — CommandStore contract")
class JdbcCommandStoreTest extends CommandStoreContract {

    static final String CHECK_VIOLATION = "23514";

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withDataSourceTransactions();
    }

    /** After every test, the head marks and the unfinished counts match the rows. */
    @AfterEach
    void theHeadsStayConsistent() {
        ((PostgresStorage) storage).assertHeadsConsistent();
    }

    @Nested
    @Order(100)
    @DisplayName("jdbc only: when a value outside the TB range is written directly to a u128 column")
    class OutOfRangeU128 {

        PostgresStorage storage;

        @BeforeEach
        void givenAStoredPost() {
            storage = PostgresStorage.withDataSourceTransactions();
            storage.transactions().inTransaction(
                    () -> storage.store().insertIfAbsent(newPost("capture-1", "order-1", 1, "hold-1")));
        }

        @ParameterizedTest(name = "{0} = {1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                transfer_id,         340282366920938463463374607431768211456
                transfer_id,         -1
                pending_transfer_id, 340282366920938463463374607431768211456
                pending_transfer_id, -1
                debit_account_id,    340282366920938463463374607431768211456
                debit_account_id,    -1
                credit_account_id,   340282366920938463463374607431768211456
                credit_account_id,   -1
                amount,              340282366920938463463374607431768211456
                amount,              -1
                """)
        @DisplayName("The schema's CHECK rejects it — even values changed with SQL cannot leave the TB range (0 to 2^128-1)")
        void outOfRangeU128ValuesAreRefusedByTheSchema(String column, BigInteger value) {
            // when
            Throwable refused = catchThrowable(() -> storage.sql()
                    .sql("UPDATE ledger_command SET " + column + " = :value WHERE command_id = 'capture-1'")
                    .param("value", new BigDecimal(value)).update());

            // then
            assertThat(sqlState(refused)).as("SQLSTATE of writing %s = %s (failure: %s)", column, value, refused)
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Nested
    @Order(101)
    @DisplayName("jdbc only: when a time column equals the current instant (now()) — now() stands still within a transaction")
    class AtTheExactInstant {

        PostgresStorage storage;

        @BeforeEach
        void givenAStore() {
            storage = PostgresStorage.withDataSourceTransactions();
        }

        @Test
        @DisplayName("An accepted command whose next attempt time is now is a candidate (next_attempt_at <= now())")
        void aPendingCommandAtItsRetryInstantIsDue() {
            // when
            List<String> due = storage.transactions().inTransaction(() -> {
                storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                storage.sql().sql("UPDATE ledger_command SET next_attempt_at = now() WHERE command_id = 'pay-1'").update();
                return dueIds();
            });

            // then
            assertThat(due).as("candidates").containsExactly("pay-1");
        }

        @Test
        @DisplayName("An in-flight command whose lease expires now is not a candidate yet (lease_expires_at < now())")
        void aClaimedCommandAtItsLeaseExpiryIsNotDue() {
            // when
            List<String> due = storage.transactions().inTransaction(() -> {
                storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                storage.sql().sql("""
                        UPDATE ledger_command SET state = 'CLAIMED', lease_token = 1, lease_expires_at = now()
                        WHERE command_id = 'pay-1'
                        """).update();
                return dueIds();
            });

            // then
            assertThat(due).as("candidates").isEmpty();
        }

        private List<String> dueIds() {
            return storage.store().lockDue(10).stream().map(d -> d.command().commandId()).toList();
        }
    }

    @Nested
    @Order(102)
    @DisplayName("jdbc only: what the schema guards — F31")
    class TheSchemaGuards {

        static final String UNIQUE_VIOLATION = "23505";

        PostgresStorage storage;

        @BeforeEach
        void givenAStore() {
            storage = PostgresStorage.withDataSourceTransactions();
        }

        @ParameterizedTest(name = "{0}.{1}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                ledger_command,      command_id
                ledger_command,      ordering_key
                ledger_command,      reservation_command_id
                ledger_ordering_key, ordering_key
                """)
        @DisplayName("ID columns compare byte for byte (COLLATE \"C\") — so unique indexes stay correct when the OS collation changes")
        void idColumnsCompareByteForByte(String table, String column) {
            // when
            String collation = storage.sql().sql("""
                    SELECT c.collname FROM pg_attribute a JOIN pg_collation c ON c.oid = a.attcollation
                    WHERE a.attrelid = :table::regclass AND a.attname = :column
                    """).param("table", table).param("column", column).query(String.class).single();

            // then
            assertThat(collation).as("collation of %s.%s", table, column).isEqualTo("C");
        }

        @Test
        @DisplayName("Giving another command the same TB transfer id is rejected — if transfer ids collide, TB's exists marks another command as applied")
        void aDuplicateTransferIdIsRefused() {
            // given
            storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1)));

            // when
            Throwable refused = catchThrowable(() -> storage.sql().sql("""
                    UPDATE ledger_command SET command_id = 'pay-2', ordering_key = 'order-2'
                    WHERE command_id = 'pay-1'
                    """).update());
            storage.rebuildHeads();
            Throwable duplicate = catchThrowable(() -> storage.transactions().inTransaction(() -> storage.store()
                    .insertIfAbsent(new NewCommand("pay-3", "order-3", 1, Optional.empty(),
                            record("pay-2").transferId(), "hash-of-pay-3", newTransfer("pay-3", "order-3", 1).body()))));

            // then
            assertThat(refused).as("row with only its names changed").isNull();
            assertThat(sqlState(duplicate)).as("new command with the same transfer id (failure: %s)", duplicate).isEqualTo(UNIQUE_VIOLATION);
        }

        @ParameterizedTest(name = "{0} → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                post loses its reservation,   UPDATE ledger_command SET reservation_command_id = NULL WHERE command_id = 'capture-1', ledger_command_reference_check
                in flight without a lease,    UPDATE ledger_command SET state = 'CLAIMED' WHERE command_id = 'capture-1',            ledger_command_claimed_lease_check
                sequence number is 0,         UPDATE ledger_command SET seq = 0 WHERE command_id = 'capture-1',                      ledger_command_seq_check
                """)
        @DisplayName("Rows that break the Java invariants are rejected by named CHECK constraints")
        void rowsThatBreakTheInvariantsAreRefused(String meaning, String update, String constraint) {
            // given
            storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newPost("capture-1", "order-1", 2,
                    "hold-1")));

            // when
            Throwable refused = catchThrowable(() -> storage.sql().sql(update).update());

            // then
            assertThat(sqlState(refused)).as("%s (failure: %s)", meaning, refused).isEqualTo(CHECK_VIOLATION);
            assertThat(refused).as("constraint that rejected: %s", meaning).hasMessageContaining(constraint);
        }

        @ParameterizedTest(name = "{0}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                known_result
                result_code
                """)
        @DisplayName("An unknown stored name in a result code column is rejected — so the claim does not halt everything reading that row (F29)")
        void anUnknownResultCodeIsRefused(String column) {
            // given
            storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1)));

            // when
            Throwable refused = catchThrowable(() -> storage.sql()
                    .sql("UPDATE ledger_command SET " + column + " = 'made_up_code' WHERE command_id = 'pay-1'").update());

            // then
            assertThat(sqlState(refused)).as("unknown value in %s (failure: %s)", column, refused).isEqualTo(CHECK_VIOLATION);
        }

        @Test
        @DisplayName("Every stored name of a result code is accepted — so a TB upgrade that adds codes forces a schema update too")
        void everyWireNameIsAccepted() {
            // given
            storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1)));

            // when
            List<String> refused = new java.util.ArrayList<>();
            for (io.github.ledgerrelay.core.LedgerResultCode code : io.github.ledgerrelay.core.LedgerResultCode.values()) {
                Throwable failure = catchThrowable(() -> storage.sql()
                        .sql("UPDATE ledger_command SET known_result = :code, result_code = :code WHERE command_id = 'pay-1'")
                        .param("code", code.wireName()).update());
                if (failure != null) {
                    refused.add(code.wireName());
                }
            }

            // then
            assertThat(refused).as("stored names the schema rejected").isEmpty();
        }

        @Test
        @DisplayName("A find that became a prepared statement on the same connection does not break after an ADD COLUMN in production — SELECT * is not used")
        void findSurvivesAnAddedColumn() {
            // given: the same find ran six times on one connection and became a server-side prepared statement (pgjdbc prepareThreshold 5)
            var single = PostgresStorage.singleConnection();
            try {
                JdbcCommandStore onOneConnection = new JdbcCommandStore(single);
                storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newTransfer("pay-1", "order-1",
                        1)));
                for (int i = 0; i < 6; i++) {
                    onOneConnection.find("pay-1");
                }
                storage.sql().sql("ALTER TABLE ledger_command ADD COLUMN migration_probe int").update();

                // when: a find inside an app transaction (what a submit does)
                var inAppTransaction = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(single));
                Throwable broken = catchThrowable(() -> inAppTransaction.execute(status -> onOneConnection.find("pay-1")));

                // then
                assertThat(broken).as("find after a column was added").isNull();
            } finally {
                storage.sql().sql("ALTER TABLE ledger_command DROP COLUMN IF EXISTS migration_probe").update();
                single.destroy();
            }
        }

        private CommandRecord record(String commandId) {
            return storage.transactions().inTransaction(() -> storage.store().find(commandId)).orElseThrow();
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
