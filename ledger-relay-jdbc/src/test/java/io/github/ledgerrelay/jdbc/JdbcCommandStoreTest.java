package io.github.ledgerrelay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ledgerrelay.core.testing.CommandStoreContract;
import io.github.ledgerrelay.core.testing.StorageUnderTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
