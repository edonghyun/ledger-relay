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

@DisplayName("PostgreSQL 저장소 — CommandStore 계약")
class JdbcCommandStoreTest extends CommandStoreContract {

    static final String CHECK_VIOLATION = "23514";

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withDataSourceTransactions();
    }

    /** 어느 장이 끝나도 맨 앞 표시와 미완료 개수가 행과 맞다. */
    @AfterEach
    void theHeadsStayConsistent() {
        ((PostgresStorage) storage).assertHeadsConsistent();
    }

    @Nested
    @Order(100)
    @DisplayName("jdbc 전용: u128 컬럼에 TB 범위를 벗어난 값을 직접 넣으면")
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
        @DisplayName("스키마의 CHECK가 거부한다 — SQL로 고친 값도 TB 범위(0 ~ 2^128-1)를 벗어나지 못한다")
        void outOfRangeU128ValuesAreRefusedByTheSchema(String column, BigInteger value) {
            // when
            Throwable refused = catchThrowable(() -> storage.sql()
                    .sql("UPDATE ledger_command SET " + column + " = :value WHERE command_id = 'capture-1'")
                    .param("value", new BigDecimal(value)).update());

            // then
            assertThat(sqlState(refused)).as("%s = %s를 쓴 결과의 SQLSTATE (실패: %s)", column, value, refused)
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Nested
    @Order(101)
    @DisplayName("jdbc 전용: 시각 컬럼이 바로 지금(now())과 같으면 — 한 트랜잭션 안에서 now()는 멈춰 있다")
    class AtTheExactInstant {

        PostgresStorage storage;

        @BeforeEach
        void givenAStore() {
            storage = PostgresStorage.withDataSourceTransactions();
        }

        @Test
        @DisplayName("다음 시도 시각이 지금인 접수 명령은 후보다 (next_attempt_at <= now())")
        void aPendingCommandAtItsRetryInstantIsDue() {
            // when
            List<String> due = storage.transactions().inTransaction(() -> {
                storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1));
                storage.sql().sql("UPDATE ledger_command SET next_attempt_at = now() WHERE command_id = 'pay-1'").update();
                return dueIds();
            });

            // then
            assertThat(due).as("후보").containsExactly("pay-1");
        }

        @Test
        @DisplayName("lease 만료 시각이 지금인 처리 중 명령은 아직 후보가 아니다 (lease_expires_at < now())")
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
            assertThat(due).as("후보").isEmpty();
        }

        private List<String> dueIds() {
            return storage.store().lockDue(10).stream().map(d -> d.command().commandId()).toList();
        }
    }

    @Nested
    @Order(102)
    @DisplayName("jdbc 전용: 스키마가 지키는 것 — F31")
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
        @DisplayName("ID 컬럼은 바이트로 비교한다(COLLATE \"C\") — OS collation이 바뀌어도 유일 인덱스가 어긋나지 않게")
        void idColumnsCompareByteForByte(String table, String column) {
            // when
            String collation = storage.sql().sql("""
                    SELECT c.collname FROM pg_attribute a JOIN pg_collation c ON c.oid = a.attcollation
                    WHERE a.attrelid = :table::regclass AND a.attname = :column
                    """).param("table", table).param("column", column).query(String.class).single();

            // then
            assertThat(collation).as("%s.%s의 collation", table, column).isEqualTo("C");
        }

        @Test
        @DisplayName("다른 명령에 같은 TB 이체 ID를 넣으면 거부된다 — 이체 ID가 겹치면 TB의 exists가 남의 명령을 적용됨으로 만든다")
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
            assertThat(refused).as("이름만 바꾼 행").isNull();
            assertThat(sqlState(duplicate)).as("같은 이체 ID의 새 명령 (실패: %s)", duplicate).isEqualTo(UNIQUE_VIOLATION);
        }

        @ParameterizedTest(name = "{0} → {2}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                확정의 참조 예약을 지우면,          UPDATE ledger_command SET reservation_command_id = NULL WHERE command_id = 'capture-1', ledger_command_reference_check
                처리 중인데 lease가 없으면,         UPDATE ledger_command SET state = 'CLAIMED' WHERE command_id = 'capture-1',            ledger_command_claimed_lease_check
                순번이 0이면,                       UPDATE ledger_command SET seq = 0 WHERE command_id = 'capture-1',                      ledger_command_seq_check
                """)
        @DisplayName("Java 불변식과 어긋나는 행은 이름 붙은 CHECK가 거부한다")
        void rowsThatBreakTheInvariantsAreRefused(String meaning, String update, String constraint) {
            // given
            storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newPost("capture-1", "order-1", 2,
                    "hold-1")));

            // when
            Throwable refused = catchThrowable(() -> storage.sql().sql(update).update());

            // then
            assertThat(sqlState(refused)).as("%s (실패: %s)", meaning, refused).isEqualTo(CHECK_VIOLATION);
            assertThat(refused).as("%s를 막은 제약", meaning).hasMessageContaining(constraint);
        }

        @ParameterizedTest(name = "{0}", quoteTextArguments = false)
        @CsvSource(textBlock = """
                known_result
                result_code
                """)
        @DisplayName("결과 코드 칸에 모르는 저장 이름을 넣으면 거부된다 — claim이 그 행을 읽다 전체가 멈추지 않게 (F29)")
        void anUnknownResultCodeIsRefused(String column) {
            // given
            storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newTransfer("pay-1", "order-1", 1)));

            // when
            Throwable refused = catchThrowable(() -> storage.sql()
                    .sql("UPDATE ledger_command SET " + column + " = 'made_up_code' WHERE command_id = 'pay-1'").update());

            // then
            assertThat(sqlState(refused)).as("%s에 모르는 값 (실패: %s)", column, refused).isEqualTo(CHECK_VIOLATION);
        }

        @Test
        @DisplayName("결과 코드의 저장 이름은 모두 받아들인다 — TB를 올려 코드가 늘면 스키마도 함께 고치게")
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
            assertThat(refused).as("스키마가 거부한 저장 이름").isEmpty();
        }

        @Test
        @DisplayName("같은 커넥션에서 준비 문장이 된 조회도 운영 중 ADD COLUMN 뒤에 깨지지 않는다 — SELECT * 를 쓰지 않는다")
        void findSurvivesAnAddedColumn() {
            // given: 커넥션 하나로 같은 조회를 여섯 번 해 서버 준비 문장으로 만들었다(pgjdbc prepareThreshold 5)
            var single = PostgresStorage.singleConnection();
            try {
                JdbcCommandStore onOneConnection = new JdbcCommandStore(single);
                storage.transactions().inTransaction(() -> storage.store().insertIfAbsent(newTransfer("pay-1", "order-1",
                        1)));
                for (int i = 0; i < 6; i++) {
                    onOneConnection.find("pay-1");
                }
                storage.sql().sql("ALTER TABLE ledger_command ADD COLUMN migration_probe int").update();

                // when: 앱 트랜잭션 안의 조회(접수가 하는 것)
                var inAppTransaction = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(single));
                Throwable broken = catchThrowable(() -> inAppTransaction.execute(status -> onOneConnection.find("pay-1")));

                // then
                assertThat(broken).as("컬럼이 늘어난 뒤의 조회").isNull();
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
