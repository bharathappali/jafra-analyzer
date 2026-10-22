package io.jafra.analyzer.v2.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class V2Times {
    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private V2Times() {}

    public static String text(long epochMs) {
        return FORMAT.format(Instant.ofEpochMilli(epochMs));
    }

    public static String text(Instant instant) {
        return FORMAT.format(instant);
    }

    public static Instant parse(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw V2Exception.badRange(name + " is empty");
        }
        String value = raw.trim();
        if (value.matches("-?\\d+")) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value));
            } catch (NumberFormatException error) {
                throw V2Exception.badRange(name + " is not a timestamp");
            }
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // try offset form next
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException error) {
            throw V2Exception.badRange(name + " must be epoch milliseconds or RFC 3339 UTC");
        }
    }
}
