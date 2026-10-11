package io.jafra.analyzer.recordings;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses short duration literals for event filters (e.g. {@code 20ms}, {@code 1s}). */
public final class JfrDuration {
    private static final Pattern DURATION = Pattern.compile(
            "^\\s*(\\d+)\\s*(ms|millis|s|sec|secs|second|seconds|m|min|mins|minute|minutes)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private JfrDuration() {}

    /** Duration in nanoseconds, or {@code null} when {@code raw} is blank. */
    public static Long parseNanos(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid minDuration: " + raw);
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "ms", "millis" -> amount * 1_000_000L;
            case "s", "sec", "secs", "second", "seconds" -> amount * 1_000_000_000L;
            case "m", "min", "mins", "minute", "minutes" -> amount * 60L * 1_000_000_000L;
            default -> throw new IllegalArgumentException("invalid minDuration unit: " + unit);
        };
    }
}
