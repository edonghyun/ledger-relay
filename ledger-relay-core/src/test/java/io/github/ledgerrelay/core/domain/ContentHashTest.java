package io.github.ledgerrelay.core.domain;

import static io.github.ledgerrelay.core.LedgerCommand.AMOUNT_MAX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ledgerrelay.core.LedgerCommand;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Content hash — normalization, G7 content integrity")
class ContentHashTest {

    @Nested
    @Order(1)
    @DisplayName("When the content is the same")
    class SameContent {

        @Test
        @DisplayName("The hash is the same whatever order the values go into the builder")
        void builderOrderDoesNotMatter() {
            // given
            LedgerCommand one = LedgerCommand.transfer("pay-1").orderingKey("order-42")
                    .debit(1001).credit(2002).amount(500).ledger(1).code(10).build();
            LedgerCommand other = LedgerCommand.transfer("pay-1").code(10).ledger(1)
                    .amount(500).credit(2002).debit(1001).orderingKey("order-42").build();

            // then
            assertSameHash(one, other);
        }

        @Test
        @DisplayName("The hash is the same even if commandId differs — commandId is what is compared on, not content")
        void commandIdIsNotContent() {
            // given
            LedgerCommand one = aTransfer("pay-1").build();
            LedgerCommand other = aTransfer("pay-2").build();

            // then
            assertSameHash(one, other);
        }

        @Test
        @DisplayName("A post without an amount and a post with AMOUNT_MAX have the same hash")
        void omittedPostAmountEqualsAmountMax() {
            // given
            LedgerCommand omitted = aPost().build();
            LedgerCommand explicit = aPost().amount(AMOUNT_MAX).build();

            // then
            assertSameHash(omitted, explicit);
        }

        @Test
        @DisplayName("A transfer without user_data and a transfer with 0 written have the same hash")
        void omittedUserDataEqualsZero() {
            // given
            LedgerCommand omitted = aTransfer("pay-1").build();
            LedgerCommand explicit = aTransfer("pay-1").userData64(0).userData32(0).build();

            // then
            assertSameHash(omitted, explicit);
        }
    }

    @Nested
    @Order(2)
    @DisplayName("When even one part of the content differs")
    class DifferentContent {

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("oneFieldApart")
        @DisplayName("The hash differs")
        void hashDiffers(LedgerCommand one, LedgerCommand other, String field) {
            assertDifferentHash(one, other);
        }

        static Stream<Arguments> oneFieldApart() {
            return Stream.of(
                    argumentSet("different kind (transfer vs reservation)",
                            aTransfer("c").build(), aReserveLikeTheTransfer("c").build(), "kind"),
                    argumentSet("different orderingKey",
                            aTransfer("c").build(), aTransfer("c").orderingKey("order-43").build(), "orderingKey"),
                    argumentSet("different debit account",
                            aTransfer("c").build(), aTransfer("c").debit(1003).build(), "debitAccountId"),
                    argumentSet("different credit account",
                            aTransfer("c").build(), aTransfer("c").credit(2003).build(), "creditAccountId"),
                    argumentSet("different amount",
                            aTransfer("c").build(), aTransfer("c").amount(501).build(), "amount"),
                    argumentSet("different ledger",
                            aTransfer("c").build(), aTransfer("c").ledger(2).build(), "ledger"),
                    argumentSet("different code",
                            aTransfer("c").build(), aTransfer("c").code(11).build(), "code"),
                    argumentSet("different code (10 vs 266: the same low 8 bits)",
                            aTransfer("c").build(), aTransfer("c").code(266).build(), "code"),
                    argumentSet("different timeoutSeconds",
                            aReserveLikeTheTransfer("c").timeoutSeconds(30).build(),
                            aReserveLikeTheTransfer("c").timeoutSeconds(31).build(), "timeoutSeconds"),
                    argumentSet("different reservation",
                            aPost().build(), aPost().reservation("hold-2").build(), "reservationCommandId"),
                    argumentSet("different user_data_64",
                            aTransfer("c").build(), aTransfer("c").userData64(7).build(), "userData64"),
                    argumentSet("different user_data_32",
                            aTransfer("c").build(), aTransfer("c").userData32(7).build(), "userData32"));
        }

        @Test
        @DisplayName("The table above leaves out no hashed field")
        void tableCoversEveryHashedField() {
            // given: the third column of each row is the field that row changes
            List<Object> coveredByTheTable = oneFieldApart().map(row -> row.get()[2]).toList();

            // then
            assertThat(coveredByTheTable).as("fields the table varies").containsAll(ContentHash.HASHED_FIELDS);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("The hash is stored in the DB, so the v1 encoding is fixed — changing it requires raising VERSION")
    class FixedEncoding {

        @Test
        @DisplayName("The hash of the base transfer (order-42, 1001 → 2002, 500, ledger 1, code 10) is 13ff1a83…")
        void knownVectorForTheReferenceTransfer() {
            // when
            String hash = ContentHash.of(aTransfer("pay-1").build());

            // then
            assertPinnedHash(hash, "13ff1a83e735898e91f4e1f133b9818de72293f991856e926a0f4f8021c85b09");
        }

        @Test
        @DisplayName("user_data is written as unsigned integers: -1 reads as 2^64-1 and 2^32-1")
        void userDataIsHashedUnsigned() {
            // when
            LedgerCommand command = aTransfer("pay-1").userData64(-1L).userData32(-1).build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=8:transfer
                    orderingKey=8:order-42
                    debitAccountId=4:1001
                    creditAccountId=4:2002
                    amount=3:500
                    ledger=1:1
                    code=2:10
                    timeoutSeconds=1:0
                    reservationCommandId=0:
                    userData64=20:18446744073709551615
                    userData32=10:4294967295
                    """);
        }

        @Test
        @DisplayName("A Hangul ordering key is written with its UTF-8 byte length: two Hangul syllables plus \"-42\" make 9 bytes (3 bytes per syllable + 3)")
        void nonAsciiValuesUseUtf8ByteLength() {
            // when
            // The Hangul key is test data: each syllable is 3 bytes in UTF-8.
            LedgerCommand command = aTransfer("pay-1").orderingKey("주문-42").build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=8:transfer
                    orderingKey=9:주문-42
                    debitAccountId=4:1001
                    creditAccountId=4:2002
                    amount=3:500
                    ledger=1:1
                    code=2:10
                    timeoutSeconds=1:0
                    reservationCommandId=0:
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("A post without an amount (all of hold-1) is written with accounts 0, the 39-digit AMOUNT_MAX, and the reservation")
        void knownVectorForAFullPost() {
            // when
            LedgerCommand command = aPost().build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=4:post
                    orderingKey=8:order-42
                    debitAccountId=1:0
                    creditAccountId=1:0
                    amount=39:340282366920938463463374607431768211455
                    ledger=1:0
                    code=1:0
                    timeoutSeconds=1:0
                    reservationCommandId=6:hold-1
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("A void (hold-1) writes kind \"void\" in lower case regardless of locale")
        void knownVectorForAVoid() {
            // when
            LedgerCommand command = LedgerCommand.voidReservation("release-1").orderingKey("order-42")
                    .reservation("hold-1").build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=4:void
                    orderingKey=8:order-42
                    debitAccountId=1:0
                    creditAccountId=1:0
                    amount=1:0
                    ledger=1:0
                    code=1:0
                    timeoutSeconds=1:0
                    reservationCommandId=6:hold-1
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("A reservation expiring in 2^32-1 seconds writes the timeout with 10 digits without truncating it to int")
        void knownVectorForTheLongestReserveTimeout() {
            // when
            LedgerCommand command = aReserveLikeTheTransfer("hold-1").timeoutSeconds(4_294_967_295L).build();

            // then
            assertEncodedAs(command, """
                    v1
                    kind=7:reserve
                    orderingKey=8:order-42
                    debitAccountId=4:1001
                    creditAccountId=4:2002
                    amount=3:500
                    ledger=1:1
                    code=2:10
                    timeoutSeconds=10:4294967295
                    reservationCommandId=0:
                    userData64=1:0
                    userData32=1:0
                    """);
        }

        @Test
        @DisplayName("The hashed fields are exactly the command's fields (except commandId) — so a new field is not left out of the hash")
        void hashedFieldsMatchTheCommandRecord() {
            // given
            List<String> commandFields = Arrays.stream(LedgerCommand.class.getRecordComponents())
                    .map(component -> component.getName())
                    .filter(name -> !name.equals("commandId"))
                    .toList();

            // then
            assertThat(ContentHash.HASHED_FIELDS).containsExactlyElementsOf(commandFields);
        }
    }

    // ── Helpers used only in this file ──────────────────────────────────────────

    /** Base transfer: 500 from 1001 to 2002 on key order-42, ledger 1, code 10. */
    private static LedgerCommand.Builder aTransfer(String commandId) {
        return LedgerCommand.transfer(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10);
    }

    /** A reservation with the same values as the base transfer. */
    private static LedgerCommand.Builder aReserveLikeTheTransfer(String commandId) {
        return LedgerCommand.reserve(commandId).orderingKey("order-42")
                .debit(1001).credit(2002).amount(500).ledger(1).code(10);
    }

    /** Base post: full post of hold-1. */
    private static LedgerCommand.Builder aPost() {
        return LedgerCommand.post("capture-1").orderingKey("order-42").reservation("hold-1");
    }

    /**
     * Checks against the SHA-256 of the hand-written v1 canonical text. The expected value is text, so when it fails
     * the reader can see which line changed. The text's final newline is the '\n' at the end of the encoding.
     */
    private static void assertEncodedAs(LedgerCommand command, String canonicalText) {
        assertPinnedHash(ContentHash.of(command), sha256Hex(canonicalText));
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The pinned hash was computed by hand. If it differs, do not fix the expected value; find out why the encoding
     * changed.
     */
    private static void assertPinnedHash(String actual, String pinned) {
        assertThat(actual)
                .as("the v1 encoding changed, which breaks comparison with content_hash values already stored — "
                        + "if the change is intended, raise ContentHash.VERSION and decide how to compare with the previous version")
                .isEqualTo(pinned);
    }

    private static void assertSameHash(LedgerCommand one, LedgerCommand other) {
        assertThat(ContentHash.of(other))
                .as("expected the same content (same hash): %s / %s", one, other)
                .isEqualTo(ContentHash.of(one));
    }

    private static void assertDifferentHash(LedgerCommand one, LedgerCommand other) {
        assertThat(ContentHash.of(other))
                .as("expected different content (different hash): %s / %s", one, other)
                .isNotEqualTo(ContentHash.of(one));
    }
}
