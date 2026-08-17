package io.jafra.analyzer.recordings;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReportWindow {
    private static final Duration MAX_LAST = Duration.ofDays(7);
    private static final Pattern LAST = Pattern.compile(
            "^\\s*(\\d+)\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final Instant from;
    private final Instant to;
    private final String last;

    public ReportWindow(Instant from, Instant to, String last) {
        this.from = from;
        this.to = to;
        this.last = last;
    }

    public static ReportWindow parse(
            Instant now,
            String last,
            String from,
            String to,
            String before,
            String after) {
        boolean hasLast = present(last);
        boolean hasFrom = present(from);
        boolean hasTo = present(to);
        boolean hasBefore = present(before);
        boolean hasAfter = present(after);
        int families = 0;
        if (hasLast) {
            families++;
        }
        if (hasFrom || hasTo) {
            families++;
        }
        if (hasBefore) {
            families++;
        }
        if (hasAfter) {
            families++;
        }
        if (families == 0) {
            return null;
        }
        if (families > 1) {
            throw new IllegalArgumentException(
                    "use only one of last, from/to, before, or after");
        }
        Instant clock = now == null ? Instant.now() : now;
        if (hasLast) {
            Duration duration = parseLast(last);
            return new ReportWindow(clock.minus(duration), clock, last.trim());
        }
        if (hasBefore) {
            return new ReportWindow(null, parseTimestamp(before, "before"), null);
        }
        if (hasAfter) {
            return new ReportWindow(parseTimestamp(after, "after"), clock, null);
        }
        Instant start = hasFrom ? parseTimestamp(from, "from") : null;
        Instant end = hasTo ? parseTimestamp(to, "to") : clock;
        if (start != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("from must be earlier than to");
        }
        return new ReportWindow(start, end, null);
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }

    public String last() {
        return last;
    }

    public Instant resolvedFrom(Instant coverageStart) {
        return from != null ? from : coverageStart;
    }

    public Instant resolvedTo(Instant coverageStop) {
        return to != null ? to : coverageStop;
    }

    public static Duration parseLast(String raw) {
        Matcher matcher = LAST.matcher(raw == null ? "" : raw);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "invalid last duration '" + raw + "'; use 5m, 10mins, 1h, or 1 hour");
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new IllegalArgumentException("last duration must be greater than zero");
        }
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        Duration duration = switch (unit) {
            case "s", "sec", "secs", "second", "seconds" -> Duration.ofSeconds(amount);
            case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
            case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
            case "d", "day", "days" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("invalid last duration '" + raw + "'");
        };
        if (duration.compareTo(MAX_LAST) > 0) {
            throw new IllegalArgumentException("last duration must be at most 7d");
        }
        return duration;
    }

    public static Instant parseTimestamp(String raw, String name) {
        if (!present(raw)) {
            throw new IllegalArgumentException(name + " timestamp is required");
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        String withT = value.replace(' ', 'T');
        try {
            return Instant.parse(withT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(withT + "Z");
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException(
                    "invalid " + name + " timestamp '" + raw + "'; use ISO-8601, for example 2026-08-17T09:19:02Z");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
