package io.github.ledgerrelay.jdbc;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.SkipReason;
import io.github.ledgerrelay.core.domain.CommandRow;
import io.github.ledgerrelay.core.domain.StoredState;
import io.github.ledgerrelay.core.spi.CommandRecord;
import io.github.ledgerrelay.core.spi.NewCommand;
import io.github.ledgerrelay.core.spi.TransferBody;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ledger_command} 행과 core 기록 사이의 변환. 이름은 컬럼 이름과 같다. 부호 없는 값은 TB와 같은 숫자로 저장한다
 * (P2b-5): u64는 {@code Long.toUnsignedString}, u32는 {@code Integer.toUnsignedLong}. 열거값은 P2b-6.
 */
final class CommandRows {

    private CommandRows() {
    }

    /** {@code JdbcCommandStore.COLUMNS}와 {@code accepted_in_this_transaction}을 고른 한 행을 읽는다. */
    static CommandRecord read(ResultSet rs) throws SQLException {
        TransferBody body = new TransferBody(
                CommandKind.valueOf(rs.getString("kind")),
                u128(rs, "debit_account_id"),
                u128(rs, "credit_account_id"),
                u128(rs, "amount"),
                rs.getLong("ledger"),
                rs.getInt("code"),
                rs.getLong("timeout_seconds"),
                Optional.ofNullable(rs.getBigDecimal("pending_transfer_id")).map(BigDecimal::toBigIntegerExact),
                Long.parseUnsignedLong(rs.getBigDecimal("user_data_64").toPlainString()),
                (int) rs.getLong("user_data_32"));
        CommandRow row = new CommandRow(
                StoredState.valueOf(rs.getString("state")),
                rs.getLong("lease_token"),
                rs.getLong("submit_count"),
                resultCode(rs, "known_result"),
                resultCode(rs, "result_code"),
                rs.getInt("handler_failures"),
                Optional.ofNullable(rs.getString("skip_reason")).map(SkipReason::valueOf));
        return new CommandRecord(
                rs.getLong("id"),
                rs.getString("command_id"),
                rs.getString("ordering_key"),
                rs.getLong("seq"),
                Optional.ofNullable(rs.getString("reservation_command_id")),
                u128(rs, "transfer_id"),
                rs.getString("content_hash"),
                body,
                row,
                instant(rs, "accepted_at"),
                Optional.ofNullable(rs.getObject("completed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                rs.getBoolean("accepted_in_this_transaction"));
    }

    /** {@code INSERT}의 이름 붙은 인자. */
    static Map<String, Object> parameters(NewCommand command) {
        TransferBody body = command.body();
        Map<String, Object> p = new HashMap<>();
        p.put("command_id", command.commandId());
        p.put("transfer_id", new BigDecimal(command.transferId()));
        p.put("ordering_key", command.orderingKey());
        p.put("seq", command.seq());
        p.put("kind", body.kind().name());
        p.put("reservation_command_id", command.reservationCommandId().orElse(null));
        p.put("pending_transfer_id", body.pendingTransferId().map(BigDecimal::new).orElse(null));
        p.put("debit_account_id", new BigDecimal(body.debitAccountId()));
        p.put("credit_account_id", new BigDecimal(body.creditAccountId()));
        p.put("amount", new BigDecimal(body.amount()));
        p.put("ledger", body.ledger());
        p.put("code", body.code());
        p.put("timeout_seconds", body.timeoutSeconds());
        p.put("user_data_64", new BigDecimal(Long.toUnsignedString(body.userData64())));
        p.put("user_data_32", Integer.toUnsignedLong(body.userData32()));
        p.put("content_hash", command.contentHash());
        return p;
    }

    static String wireName(Optional<LedgerResultCode> code) {
        return code.map(LedgerResultCode::wireName).orElse(null);
    }

    private static BigInteger u128(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).toBigIntegerExact();
    }

    private static Optional<LedgerResultCode> resultCode(ResultSet rs, String column) throws SQLException {
        return Optional.ofNullable(rs.getString(column)).map(LedgerResultCode::fromWireName);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
