package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * One ledger command. Each command maps to exactly one TB transfer id.
 *
 * <p>Values are stored normalized. An omitted optional value becomes an explicit default. post and void inherit the
 * accounts, ledger and code from the reservation, so those are 0. A post with no amount is {@link #AMOUNT_MAX} (post
 * the full amount). A rule violation throws {@link InvalidCommandException}. Calling the constructor directly, without
 * the builder, runs the same checks.
 *
 * <p>A full post omits the amount (AMOUNT_MAX). TB stores the amount actually posted, and resending the same body
 * returns {@code exists}. The same holds for void. For post and void, user_data_64 and user_data_32 take the
 * reservation's value when they are 0 (TB 0.17.9, {@code ReservationSemanticsTest}).
 */
public record LedgerCommand(
        String commandId,
        CommandKind kind,
        String orderingKey,
        BigInteger debitAccountId,
        BigInteger creditAccountId,
        BigInteger amount,
        long ledger,
        int code,
        long timeoutSeconds,
        String reservationCommandId,
        long userData64,
        int userData32) {

    public static final BigInteger UINT128_MAX = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    /** A post with no amount gets this value. TB reads it as "the full reserved amount". */
    public static final BigInteger AMOUNT_MAX = UINT128_MAX;
    static final long UINT32_MAX = 0xFFFF_FFFFL;
    static final int UINT16_MAX = 0xFFFF;
    /**
     * Upper limit on the UTF-8 length of an id string. Well below the PG b-tree index entry limit (about 2.7 KB) (F30).
     */
    public static final int MAX_ID_BYTES = 512;

    public LedgerCommand {
        Objects.requireNonNull(kind, "kind");
        require(debitAccountId != null, "debitAccountId is required");
        require(creditAccountId != null, "creditAccountId is required");
        require(commandId != null && !commandId.isBlank(), "commandId is required");
        require(orderingKey != null && !orderingKey.isBlank(), "orderingKey is required");
        requireWellFormed("commandId", commandId);
        requireWellFormed("orderingKey", orderingKey);
        if (reservationCommandId != null) {
            requireWellFormed("reservationCommandId", reservationCommandId);
        }
        require(amount != null, "amount is required");
        require(amount.signum() >= 0 && amount.compareTo(UINT128_MAX) <= 0, "amount must be between 0 and 2^128-1");
        require(timeoutSeconds >= 0 && timeoutSeconds <= UINT32_MAX, "timeoutSeconds must be between 0 and 2^32-1");
        require(kind == CommandKind.RESERVE || timeoutSeconds == 0, "timeoutSeconds is only for reserve");
        if (kind.referencesReservation()) {
            require(reservationCommandId != null && !reservationCommandId.isBlank(),
                    "post and void require a reservation");
            require(!reservationCommandId.equals(commandId), "post and void cannot refer to themselves as the reservation");
            require(debitAccountId.signum() == 0 && creditAccountId.signum() == 0 && ledger == 0 && code == 0,
                    "post and void take the accounts, ledger and code from the reservation, so do not set debit, credit, ledger or code");
            require(kind != CommandKind.VOID || amount.signum() == 0, "void does not take an amount");
            // TB handles a post of 0 as "post 0 and void the rest". The result equals a void, so callers must use void.
            require(kind != CommandKind.POST || amount.signum() > 0,
                    "post amount cannot be 0. To post the full amount, omit amount; to release the held money, use void");
        } else {
            require(reservationCommandId == null, "reservation is only for post and void");
            requireAccountId("debitAccountId", debitAccountId);
            requireAccountId("creditAccountId", creditAccountId);
            require(!debitAccountId.equals(creditAccountId),
                    "debitAccountId and creditAccountId cannot be the same account");
            require(ledger >= 1 && ledger <= UINT32_MAX, "ledger must be between 1 and 2^32-1");
            require(code >= 1 && code <= UINT16_MAX, "code must be between 1 and 65535");
        }
    }

    public static Builder transfer(String commandId) {
        return new Builder(CommandKind.TRANSFER, commandId);
    }

    public static Builder reserve(String commandId) {
        return new Builder(CommandKind.RESERVE, commandId);
    }

    public static Builder post(String commandId) {
        return new Builder(CommandKind.POST, commandId);
    }

    public static Builder voidReservation(String commandId) {
        return new Builder(CommandKind.VOID, commandId);
    }

    public Optional<String> reservation() {
        return Optional.ofNullable(reservationCommandId);
    }

    private static void requireAccountId(String field, BigInteger id) {
        require(id.signum() != 0, field + " is required");
        require(id.signum() > 0 && id.compareTo(UINT128_MAX) < 0, field + " must be between 1 and 2^128-2");
    }

    /**
     * PG {@code text} rejects U+0000, so acceptance fails with an SQL error that breaks the app transaction. A lone
     * surrogate becomes '?' when encoded to UTF-8, so its hash equals that of a different value
     * (docs/failure-modes.md F20).
     */
    private static void requireWellFormed(String field, String value) {
        require(isWellFormed(value), field + " cannot contain U+0000 or a lone surrogate");
        require(fitsTheIndex(value), field + " must be at most " + MAX_ID_BYTES + " bytes in UTF-8");
    }

    private static boolean fitsTheIndex(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length <= MAX_ID_BYTES;
    }

    /**
     * Whether this string can be accepted as a commandId (not blank, no U+0000 or lone surrogate, at most 512 bytes in
     * UTF-8). If not, no command can have been accepted under that id. Services filter with this before a query or an
     * abandon and do not send such ids to the DB. In PG, U+0000 breaks the app transaction, and a lone surrogate turns
     * into '?' and matches a different command (F20).
     */
    public static boolean isAcceptableId(String commandId) {
        return commandId != null && !commandId.isBlank() && isWellFormed(commandId) && fitsTheIndex(commandId);
    }

    private static boolean isWellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean lone = Character.isHighSurrogate(c)
                    ? i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))
                    : Character.isLowSurrogate(c);
            if (c == '\u0000' || lone) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean satisfied, String reason) {
        if (!satisfied) {
            throw new InvalidCommandException(reason);
        }
    }

    public static final class Builder {
        private final CommandKind kind;
        private final String commandId;
        private String orderingKey;
        private BigInteger debit = BigInteger.ZERO;
        private BigInteger credit = BigInteger.ZERO;
        private BigInteger amount;
        private long ledger;
        private int code;
        private long timeoutSeconds;
        private String reservation;
        private long userData64;
        private int userData32;

        private Builder(CommandKind kind, String commandId) {
            this.kind = kind;
            this.commandId = commandId;
        }

        public Builder orderingKey(String orderingKey) { this.orderingKey = orderingKey; return this; }
        public Builder debit(long accountId) { return debit(BigInteger.valueOf(accountId)); }
        public Builder debit(BigInteger accountId) { this.debit = accountId; return this; }
        public Builder credit(long accountId) { return credit(BigInteger.valueOf(accountId)); }
        public Builder credit(BigInteger accountId) { this.credit = accountId; return this; }
        public Builder amount(long amount) { return amount(BigInteger.valueOf(amount)); }
        public Builder amount(BigInteger amount) { this.amount = amount; return this; }
        public Builder ledger(long ledger) { this.ledger = ledger; return this; }
        public Builder code(int code) { this.code = code; return this; }
        public Builder timeoutSeconds(long seconds) { this.timeoutSeconds = seconds; return this; }
        public Builder reservation(String reservationCommandId) { this.reservation = reservationCommandId; return this; }
        public Builder userData64(long value) { this.userData64 = value; return this; }
        public Builder userData32(int value) { this.userData32 = value; return this; }

        public LedgerCommand build() {
            return new LedgerCommand(commandId, kind, orderingKey, debit, credit, normalizedAmount(),
                    ledger, code, timeoutSeconds, reservation, userData64, userData32);
        }

        /**
         * An omitted amount: post gets the full amount, void gets 0, and transfer and reserve stay null so the
         * constructor rejects them.
         */
        private BigInteger normalizedAmount() {
            if (amount != null) {
                return amount;
            }
            return switch (kind) {
                case POST -> AMOUNT_MAX;
                case VOID -> BigInteger.ZERO;
                case TRANSFER, RESERVE -> null;
            };
        }
    }
}
