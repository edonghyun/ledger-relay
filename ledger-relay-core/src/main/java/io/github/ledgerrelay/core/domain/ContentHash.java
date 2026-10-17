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
 * 명령 내용의 정규화 해시(스펙 §5.1, G7). 같은 commandId로 다시 접수할 때 내용이 같은지를 이 값으로 비교한다.
 *
 * <p>이 값은 DB({@code content_hash})에 저장된다. 인코딩을 바꾸면 이미 저장된 명령과의 비교가 깨진다.
 * 바꿀 때는 {@link #VERSION}을 올리고 이전 버전과 어떻게 비교할지 함께 정한다.
 *
 * <p>인코딩 v1: {@code "v1\n"} 뒤에 필드마다 {@code 이름=<UTF-8 바이트 길이>:<값>\n}을 {@link #HASHED_FIELDS}
 * 순서로 붙이고 SHA-256을 씌운다. 길이를 앞에 붙이는 것은 값 안의 구분자가 경계를 흐리지 못하게 하려는 것이다.
 */
public final class ContentHash {

    public static final String VERSION = "v1";

    /** 해시에 들어가는 필드와 순서. commandId는 비교 기준이라 넣지 않는다. */
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
            throw new IllegalStateException("JDK에 SHA-256이 없습니다", e);
        }
    }
}
