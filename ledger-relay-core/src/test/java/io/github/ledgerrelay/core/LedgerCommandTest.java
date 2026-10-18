package io.github.ledgerrelay.core;

import static io.github.ledgerrelay.core.CommandKind.POST;
import static io.github.ledgerrelay.core.CommandKind.RESERVE;
import static io.github.ledgerrelay.core.CommandKind.TRANSFER;
import static io.github.ledgerrelay.core.CommandKind.VOID;
import static io.github.ledgerrelay.core.LedgerCommand.AMOUNT_MAX;
import static io.github.ledgerrelay.core.LedgerCommand.UINT128_MAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.math.BigInteger;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Command model — builder and normalization")
class LedgerCommandTest {

    @Nested
    @Order(1)
    @DisplayName("When a valid command is built")
    class ValidCommands {

        @Test
        @DisplayName("A transfer keeps the values written, and omitted user_data becomes 0")
        void transferKeepsWhatWasWritten() {
            // when
            LedgerCommand payment = LedgerCommand.transfer("pay-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();

            // then
            assertThat(payment).isEqualTo(new LedgerCommand("pay-1", TRANSFER, "order-42",
                    big(1001), big(2002), big(500), 1, 10, 0, null, 0, 0));
        }

        @Test
        @DisplayName("A reservation also carries its expiry (timeoutSeconds)")
        void reserveKeepsItsTimeout() {
            // when
            LedgerCommand hold = LedgerCommand.reserve("hold-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).timeoutSeconds(30).build();

            // then
            assertThat(hold).isEqualTo(new LedgerCommand("hold-1", RESERVE, "order-42",
                    big(1001), big(2002), big(500), 1, 10, 30, null, 0, 0));
        }

        @Test
        @DisplayName("A post without an amount becomes a full post (AMOUNT_MAX), and accounts, ledger and code become 0 so they follow the reservation")
        void postWithoutAmountCapturesEverything() {
            // when
            LedgerCommand capture = LedgerCommand.post("capture-1").orderingKey("order-42")
                    .reservation("hold-1").build();

            // then
            assertThat(capture).isEqualTo(new LedgerCommand("capture-1", POST, "order-42",
                    BigInteger.ZERO, BigInteger.ZERO, AMOUNT_MAX, 0, 0, 0, "hold-1", 0, 0));
        }

        @Test
        @DisplayName("A post with an amount posts only that amount (partial post)")
        void postWithAmountCapturesPart() {
            // when
            LedgerCommand capture = LedgerCommand.post("capture-1").orderingKey("order-42")
                    .reservation("hold-1").amount(300).build();

            // then
            assertThat(capture.amount()).isEqualTo(big(300));
        }

        @Test
        @DisplayName("A void is stored with amount 0")
        void voidHasNoAmount() {
            // when
            LedgerCommand release = LedgerCommand.voidReservation("release-1").orderingKey("order-42")
                    .reservation("hold-1").build();

            // then
            assertThat(release).isEqualTo(new LedgerCommand("release-1", VOID, "order-42",
                    BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 0, 0, 0, "hold-1", 0, 0));
        }
        @Test
        @DisplayName("A commandId of 512 UTF-8 bytes is accepted — just under the limit")
        void aCommandIdOf512BytesIsAccepted() {
            // when
            LedgerCommand payment = aTransfer("a".repeat(512)).build();

            // then
            assertThat(payment.commandId()).hasSize(512);
        }

        @Test
        @DisplayName("A paired surrogate (emoji) in the ordering key is kept as is")
        void aTransferWithAPairedSurrogateIsAccepted() {
            // when
            LedgerCommand payment = aTransfer("pay-1").orderingKey("order-😀").build();

            // then
            assertThat(payment.orderingKey()).isEqualTo("order-😀");
        }

        @Test
        @DisplayName("The largest TB field values (ledger 2^32-1, code 65535, expiry 2^32-1 seconds) are kept as is")
        void acceptsTheLargestTigerBeetleValues() {
            // when
            LedgerCommand hold = LedgerCommand.reserve("hold-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500)
                    .ledger(4_294_967_295L).code(65_535).timeoutSeconds(4_294_967_295L).build();

            // then
            assertThat(hold.ledger()).as("ledger").isEqualTo(4_294_967_295L);
            assertThat(hold.code()).as("code").isEqualTo(65_535);
            assertThat(hold.timeoutSeconds()).as("timeoutSeconds").isEqualTo(4_294_967_295L);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When a command that breaks a rule is built")
    class InvalidCommands {

        @ParameterizedTest(name = "{argumentSetName} → \"{1}\"", quoteTextArguments = false)
        @MethodSource("invalidCommands")
        @DisplayName("It cannot be built, and the error says what was wrong")
        void isRejectedWithTheReason(ThrowingCallable building, String reason) {
            assertRejectedAsInvalid(building, reason);
        }

        static Stream<Arguments> invalidCommands() {
            return Stream.of(
                    argumentSet("a blank commandId",
                            (ThrowingCallable) () -> aTransfer(" ").build(),
                            "commandId is required"),
                    argumentSet("no orderingKey",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey(null).build(), "orderingKey is required"),
                    argumentSet("debit account 0 (none)",
                            (ThrowingCallable) () -> aTransfer("pay-1").debit(0).build(), "debitAccountId is required"),
                    argumentSet("credit account 2^128-1 (reserved by TB)",
                            (ThrowingCallable) () -> aTransfer("pay-1").credit(UINT128_MAX).build(),
                            "creditAccountId must be between 1 and 2^128-2"),
                    argumentSet("the same debit and credit account",
                            (ThrowingCallable) () -> aTransfer("pay-1").credit(1001).build(), "cannot be the same account"),
                    argumentSet("a transfer without an amount",
                            (ThrowingCallable) () -> aTransferWithoutAmount().build(),
                            "amount is required"),
                    argumentSet("a negative amount",
                            (ThrowingCallable) () -> aTransfer("pay-1").amount(-1).build(), "amount must be between 0 and 2^128-1"),
                    argumentSet("an amount of 2^128 or more",
                            (ThrowingCallable) () -> aTransfer("pay-1").amount(UINT128_MAX.add(BigInteger.ONE)).build(),
                            "amount must be between 0 and 2^128-1"),
                    argumentSet("ledger 0",
                            (ThrowingCallable) () -> aTransfer("pay-1").ledger(0).build(), "ledger must be between 1 and 2^32-1"),
                    argumentSet("code above 65535",
                            (ThrowingCallable) () -> aTransfer("pay-1").code(65536).build(), "code must be between 1 and 65535"),
                    argumentSet("timeoutSeconds on a transfer",
                            (ThrowingCallable) () -> aTransfer("pay-1").timeoutSeconds(30).build(), "timeoutSeconds is only for reserve"),
                    argumentSet("reservation timeoutSeconds of 2^32 or more",
                            (ThrowingCallable) () -> aReserve().timeoutSeconds(1L << 32).build(),
                            "timeoutSeconds must be between 0 and 2^32-1"),
                    argumentSet("reservation on a transfer",
                            (ThrowingCallable) () -> aTransfer("pay-1").reservation("hold-1").build(), "reservation is only for post and void"),
                    argumentSet("a post without a reservation",
                            (ThrowingCallable) () -> LedgerCommand.post("capture-1").orderingKey("order-42").build(),
                            "post and void require a reservation"),
                    argumentSet("a debit account on a post",
                            (ThrowingCallable) () -> aPost().debit(1001).build(), "post and void take the accounts, ledger and code from the reservation"),
                    argumentSet("a post amount of 0 — TB posts 0 and releases the rest, so a user who expected a full post gets no money",
                            (ThrowingCallable) () -> aPost().amount(0).build(), "post amount cannot be 0"),
                    argumentSet("a blank orderingKey",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey(" ").build(), "orderingKey is required"),
                    argumentSet("a negative debit account",
                            (ThrowingCallable) () -> aTransfer("pay-1").debit(-5).build(), "debitAccountId must be between 1 and 2^128-2"),
                    argumentSet("ledger 2^32 or more",
                            (ThrowingCallable) () -> aTransfer("pay-1").ledger(1L << 32).build(), "ledger must be between 1 and 2^32-1"),
                    argumentSet("code 0",
                            (ThrowingCallable) () -> aTransfer("pay-1").code(0).build(), "code must be between 1 and 65535"),
                    argumentSet("a negative reservation timeoutSeconds",
                            (ThrowingCallable) () -> aReserve().timeoutSeconds(-1).build(),
                            "timeoutSeconds must be between 0 and 2^32-1"),
                    argumentSet("timeoutSeconds on a post",
                            (ThrowingCallable) () -> aPost().timeoutSeconds(30).build(), "timeoutSeconds is only for reserve"),
                    argumentSet("a credit account on a post",
                            (ThrowingCallable) () -> aPost().credit(2002).build(), "post and void take the accounts, ledger and code from the reservation"),
                    argumentSet("a ledger on a post",
                            (ThrowingCallable) () -> aPost().ledger(1).build(), "post and void take the accounts, ledger and code from the reservation"),
                    argumentSet("a code on a void",
                            (ThrowingCallable) () -> aVoid().code(10).build(), "post and void take the accounts, ledger and code from the reservation"),
                    argumentSet("a void without a reservation",
                            (ThrowingCallable) () -> LedgerCommand.voidReservation("release-1").orderingKey("order-42").build(),
                            "post and void require a reservation"),
                    argumentSet("a blank reservation on a post",
                            (ThrowingCallable) () -> aPost().reservation(" ").build(), "post and void require a reservation"),
                    argumentSet("an amount on a void",
                            (ThrowingCallable) () -> aVoid().amount(100).build(), "void does not take an amount"),
                    argumentSet("U+0000 in commandId — PG text rejects it, so the submit breaks the app transaction with an SQL error",
                            (ThrowingCallable) () -> aTransfer("pay\u00001").build(),
                            "commandId cannot contain U+0000 or a lone surrogate"),
                    argumentSet("an unpaired surrogate in the ordering key — it becomes '?' when encoded as UTF-8, so hashes collide",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey("order-\uD800").build(),
                            "orderingKey cannot contain U+0000 or a lone surrogate"),
                    argumentSet("U+0000 in a post's reservation",
                            (ThrowingCallable) () -> aPost().reservation("hold\u00001").build(),
                            "reservationCommandId cannot contain U+0000 or a lone surrogate"),
                    argumentSet("a commandId of 513 UTF-8 bytes — stopped before it reaches the PG index entry limit",
                            (ThrowingCallable) () -> aTransfer("a".repeat(513)).build(),
                            "commandId must be at most 512 bytes in UTF-8"),
                    argumentSet("an ordering key of 171 Hangul syllables (513 bytes)",
                            (ThrowingCallable) () -> aTransfer("pay-1").orderingKey("가".repeat(171)).build(),
                            "orderingKey must be at most 512 bytes in UTF-8"),
                    argumentSet("a null debit account",
                            (ThrowingCallable) () -> aTransfer("pay-1").debit((BigInteger) null).build(),
                            "debitAccountId is required"),
                    argumentSet("a null credit account",
                            (ThrowingCallable) () -> aTransfer("pay-1").credit((BigInteger) null).build(),
                            "creditAccountId is required"),
                    argumentSet("a post that names itself as the reservation",
                            (ThrowingCallable) () -> LedgerCommand.post("capture-1").orderingKey("order-42")
                                    .reservation("capture-1").build(),
                            "post and void cannot refer to themselves as the reservation"));
        }
    }

    @Nested
    @Order(3)
    @DisplayName("When asked whether a string could be accepted as a commandId — used by query and abandon before going to the DB (F20)")
    class AcceptableIds {

        @Test
        @DisplayName("513 UTF-8 bytes cannot be accepted")
        void anIdOver512BytesIsNotAcceptable() {
            // Each Hangul syllable is 3 bytes in UTF-8, so 171 of them make 513 bytes.
            assertThat(LedgerCommand.isAcceptableId("가".repeat(171))).as("whether a 513-byte id can be accepted").isFalse();
        }

        @ParameterizedTest(name = "{0} → {1}", quoteTextArguments = false)
        @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
                pay-1        | true
                order-😀     | true
                null         | false
                ' '          | false
                pay\u00001   | false
                pay-\uD800   | false
                \uDC00-pay   | false
                pay\uD800x   | false
                """)
        @DisplayName("Only when it is not blank and has no U+0000 and no unpaired surrogate")
        void answersWhetherTheIdCouldHaveBeenAccepted(String commandId, boolean acceptable) {
            // The compiler turns \\uXXXX in the table into the actual characters (U+0000, surrogate halves)
            assertThat(LedgerCommand.isAcceptableId(commandId)).as("whether %s can be accepted as a commandId", commandId)
                    .isEqualTo(acceptable);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    /**
     * Base transfer: 500 from 1001 to 2002 on key order-42, ledger 1, code 10. Each row changes only one thing from it.
     */
    private static LedgerCommand.Builder aTransfer(String commandId) {
        return aTransferWithoutAmount(commandId).amount(500);
    }

    /** The base transfer without the amount. */
    private static LedgerCommand.Builder aTransferWithoutAmount() {
        return aTransferWithoutAmount("pay-1");
    }

    private static LedgerCommand.Builder aTransferWithoutAmount(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).ledger(1).code(10);
    }

    /** Base reservation: the same values as the base transfer, with a 30-second expiry. */
    private static LedgerCommand.Builder aReserve() {
        return LedgerCommand.reserve("hold-1").orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10).timeoutSeconds(30);
    }

    /** Base post: full post of hold-1. */
    private static LedgerCommand.Builder aPost() {
        return LedgerCommand.post("capture-1").orderingKey("order-42").reservation("hold-1");
    }

    /** Base void: void of hold-1. */
    private static LedgerCommand.Builder aVoid() {
        return LedgerCommand.voidReservation("release-1").orderingKey("order-42").reservation("hold-1");
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static void assertRejectedAsInvalid(ThrowingCallable building, String reason) {
        assertThatThrownBy(building)
                .as("command cannot be built (InvalidCommandException: %s)", reason)
                .isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining(reason);
    }
}
