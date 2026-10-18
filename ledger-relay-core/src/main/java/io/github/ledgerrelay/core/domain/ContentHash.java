package io.github.ledgerrelay.core.domain;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.ledgerrelay.core.LedgerCommand;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The canonical hash of a command's content (G7). When a command is submitted again under the same commandId, this
 * value decides whether the content is the same.
 *
 * <p>The value is stored in the DB ({@code content_hash}). Changing the encoding breaks the comparison with commands
 * already stored. To change it, bump {@link #VERSION} and decide at the same time how to compare with the old version.
 *
 * <p>Encoding v1: {@code "v1\n"}, then for each field {@code name=<UTF-8 byte length>:<value>\n} in
 * {@link #HASHED_FIELDS} order, then SHA-256 over the whole. The length prefix keeps a separator inside a value from
 * blurring the field boundaries.
 */
public final class ContentHash {

    public static final String VERSION = "v1";

    /** The hashed fields and their order. commandId is the key of the comparison, so it is not included. */
    public static final List<String> HASHED_FIELDS = List.of(
            "kind", "orderingKey", "debitAccountId", "creditAccountId", "amount", "ledger", "code",
            "timeoutSeconds", "reservationCommandId", "userData64", "userData32");

    private ContentHash() {
    }

    public static String of(LedgerCommand command) {
        Map<String, String> values = canonicalValues(command);
        StringBuilder text = new StringBuilder(VERSION).append('\n');
        for (String field : HASHED_FIELDS) {
            String value = values.get(field);
            text.append(field).append('=').append(value.getBytes(UTF_8).length).append(':').append(value).append('\n');
        }
        return HexFormat.of().formatHex(sha256(text.toString().getBytes(UTF_8)));
    }

    private static Map<String, String> canonicalValues(LedgerCommand c) {
        return Map.ofEntries(
                Map.entry("kind", c.kind().name().toLowerCase(Locale.ROOT)),
                Map.entry("orderingKey", c.orderingKey()),
                Map.entry("debitAccountId", c.debitAccountId().toString()),
                Map.entry("creditAccountId", c.creditAccountId().toString()),
                Map.entry("amount", c.amount().toString()),
                Map.entry("ledger", Long.toString(c.ledger())),
                Map.entry("code", Integer.toString(c.code())),
                Map.entry("timeoutSeconds", Long.toString(c.timeoutSeconds())),
                Map.entry("reservationCommandId", c.reservation().orElse("")),
                Map.entry("userData64", Long.toUnsignedString(c.userData64())),
                Map.entry("userData32", Integer.toUnsignedString(c.userData32())));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JDK has no SHA-256", e);
        }
    }
}
