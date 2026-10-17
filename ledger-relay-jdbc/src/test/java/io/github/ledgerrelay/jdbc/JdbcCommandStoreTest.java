package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.testing.CommandStoreContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("PostgreSQL 저장소 — CommandStore 계약")
class JdbcCommandStoreTest extends CommandStoreContract {

    static final String CHECK_VIOLATION = "23514";

    @Override
    protected StorageUnderTest createStorage() {
        return PostgresStorage.withDataSourceTransactions();
    }

    @Nested
    @Order(100)
    @DisplayName("jdbc 전용: u128 컬럼에 TB 범위를 벗어난 값을 직접 넣으면 (P2c-12)")
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

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
