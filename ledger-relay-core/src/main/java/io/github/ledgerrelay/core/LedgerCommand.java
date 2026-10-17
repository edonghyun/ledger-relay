package io.github.ledgerrelay.core;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * 원장 명령 하나(스펙 §2, §6). 명령 1개에는 TB 이체 ID 1개만 대응한다(D7).
 *
 * <p>값은 정규화된 상태로 담는다(§5.1). 생략한 선택값은 명시적 기본값이 된다. post·void는 계좌·ledger·code를
 * 예약에서 물려받으므로 0이다. 금액을 생략한 post는 {@link #AMOUNT_MAX}(전액 확정)다. 규칙을 어기면
 * {@link InvalidCommandException}을 던진다. 빌더를 거치지 않고 생성자를 직접 불러도 같은 검증을 받는다.
 *
 * <p>전액 확정은 금액을 생략한다(AMOUNT_MAX). TB는 실제 확정 금액을 저장하며, 같은 본문으로 다시 보내면 {@code exists}다.
 * 해제도 같다. post·void의 user_data_64·32는 0이면 예약의 값을 따른다(TB 0.17.9, {@code ReservationSemanticsTest}).
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
    /** post에서 금액을 생략하면 이 값이 된다. TB는 이 값을 "예약 금액 전부"로 읽는다. */
    public static final BigInteger AMOUNT_MAX = UINT128_MAX;
    static final long UINT32_MAX = 0xFFFF_FFFFL;
    static final int UINT16_MAX = 0xFFFF;

    public LedgerCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(debitAccountId, "debitAccountId");
        Objects.requireNonNull(creditAccountId, "creditAccountId");
        require(commandId != null && !commandId.isBlank(), "commandId가 필요합니다");
        require(orderingKey != null && !orderingKey.isBlank(), "orderingKey가 필요합니다");
        require(amount != null, "amount가 필요합니다");
        require(amount.signum() >= 0 && amount.compareTo(UINT128_MAX) <= 0, "amount는 0 이상 2^128-1 이하여야 합니다");
        require(timeoutSeconds >= 0 && timeoutSeconds <= UINT32_MAX, "timeoutSeconds는 0 이상 2^32-1 이하여야 합니다");
        require(kind == CommandKind.RESERVE || timeoutSeconds == 0, "timeoutSeconds는 reserve 전용입니다");
        if (kind.referencesReservation()) {
            require(reservationCommandId != null && !reservationCommandId.isBlank(),
                    "post·void에는 reservation이 필요합니다");
            require(debitAccountId.signum() == 0 && creditAccountId.signum() == 0 && ledger == 0 && code == 0,
                    "post·void는 예약의 계좌·ledger·code를 따르므로 debit, credit, ledger, code를 지정하지 않습니다");
            require(kind != CommandKind.VOID || amount.signum() == 0, "void는 amount를 받지 않습니다");
            // TB는 0원 post를 "0원 확정 + 나머지 해제"로 처리한다. 그 결과는 void와 같으므로 void를 쓰게 한다.
            require(kind != CommandKind.POST || amount.signum() > 0,
                    "post의 amount는 0일 수 없습니다. 전액 확정은 amount를 생략하고, 묶인 돈을 풀려면 void를 씁니다");
        } else {
            require(reservationCommandId == null, "reservation은 post·void 전용입니다");
            requireAccountId("debitAccountId", debitAccountId);
            requireAccountId("creditAccountId", creditAccountId);
            require(!debitAccountId.equals(creditAccountId),
                    "debitAccountId와 creditAccountId는 같은 계좌일 수 없습니다");
            require(ledger >= 1 && ledger <= UINT32_MAX, "ledger는 1 이상 2^32-1 이하여야 합니다");
            require(code >= 1 && code <= UINT16_MAX, "code는 1 이상 65535 이하여야 합니다");
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
        require(id.signum() != 0, field + "가 필요합니다");
        require(id.signum() > 0 && id.compareTo(UINT128_MAX) < 0, field + "는 1 이상 2^128-2 이하여야 합니다");
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

        /** 생략한 금액: post는 전액 확정, void는 0, 이체·예약은 그대로 null이라 생성자가 거부한다. */
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
