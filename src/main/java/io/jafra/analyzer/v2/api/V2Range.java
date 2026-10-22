package io.jafra.analyzer.v2.api;

import java.time.Duration;
import java.time.Instant;

import io.jafra.analyzer.recordings.ReportWindow;

/** from/to as epoch ms or RFC 3339, or last= using the v1 duration grammar. */
public record V2Range(Instant from, Instant to) {
    public static V2Range parse(String last, String from, String to, Instant defaultEnd) {
        boolean hasLast = present(last);
        boolean hasFrom = present(from);
        boolean hasTo = present(to);
        if (hasLast && (hasFrom || hasTo)) {
            throw V2Exception.badRange("last cannot be combined with from or to");
        }
        Instant end = defaultEnd == null ? Instant.now() : defaultEnd;
        if (!hasLast && !hasFrom && !hasTo) {
            return parse("15m", null, null, end);
        }
        if (hasLast) {
            Duration duration;
            try {
                duration = ReportWindow.parseLast(last);
            } catch (IllegalArgumentException error) {
                throw V2Exception.badRange(error.getMessage());
            }
            return new V2Range(end.minus(duration), end);
        }
        Instant start = hasFrom ? V2Times.parse(from, "from") : null;
        Instant stop = hasTo ? V2Times.parse(to, "to") : end;
        if (start == null) {
            throw V2Exception.badRange("from is required when to is set");
        }
        if (!start.isBefore(stop)) {
            throw V2Exception.badRange("from must be earlier than to");
        }
        return new V2Range(start, stop);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
