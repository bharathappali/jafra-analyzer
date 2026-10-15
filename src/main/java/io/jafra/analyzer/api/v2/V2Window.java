package io.jafra.analyzer.api.v2;

import java.time.Duration;
import java.time.Instant;

/** v2 time range. {@code last} or a closed {@code from}/{@code to}. {@code before} and {@code after} stay on v1. */
public final class V2Window {
    public static final Duration DEFAULT_LAST = Duration.ofMinutes(15);
    public static final Duration MAX_LAST = Duration.ofDays(7);

    private V2Window() {}

    public static Range parse(Instant now, String last, String from, String to) {
        boolean hasLast = last != null && !last.isBlank();
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (hasLast && (hasFrom || hasTo)) {
            throw new IllegalArgumentException("use either last or from/to");
        }
        if (hasFrom != hasTo) {
            throw new IllegalArgumentException("from and to are a pair");
        }
        if (!hasLast && !hasFrom) {
            last = "15m";
            hasLast = true;
        }
        if (hasLast) {
            Duration duration = duration(last.trim());
            if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_LAST) > 0) {
                throw new IllegalArgumentException("last must be between 1s and 7d");
            }
            return new Range(now.minus(duration), now);
        }
        return new Range(instant(from), instant(to));
    }

    public static StepChoice resolveStep(String requested, Range range, String nativePeriod) {
        return resolveStep(requested, range, nativePeriod, 240);
    }

    public static StepChoice resolveStep(String requested, Range range, String nativePeriod, int maxPoints) {
        boolean auto = requested == null || requested.isBlank() || "auto".equals(requested);
        Step explicit = auto ? auto(range) : step(requested);
        Step step = explicit;
        boolean coarser = false;
        Step floor = nativeFloor(nativePeriod);
        if (step.ordinal() < floor.ordinal()) {
            step = floor;
            coarser = true;
        }
        long span = Math.max(1L, range.to().toEpochMilli() - range.from().toEpochMilli());
        while (points(span, step) > maxPoints && step.ordinal() < Step.H1.ordinal()) {
            step = Step.values()[step.ordinal() + 1];
            if (!auto) {
                coarser = true;
            }
        }
        return new StepChoice(step, coarser);
    }

    private static Step nativeFloor(String nativePeriod) {
        if (nativePeriod == null || "1ms".equals(nativePeriod) || "1s".equals(nativePeriod)) {
            return Step.S1;
        }
        return step(nativePeriod);
    }

    public static Step auto(Range range) {
        long span = Math.max(1L, range.to().toEpochMilli() - range.from().toEpochMilli());
        if (span <= Duration.ofMinutes(4).toMillis()) {
            return Step.S1;
        }
        if (span <= Duration.ofMinutes(40).toMillis()) {
            return Step.S10;
        }
        if (span <= Duration.ofHours(4).toMillis()) {
            return Step.M1;
        }
        if (span <= Duration.ofHours(20).toMillis()) {
            return Step.M5;
        }
        return Step.H1;
    }

    public static Step step(String text) {
        return switch (text) {
            case "1s" -> Step.S1;
            case "10s" -> Step.S10;
            case "1m" -> Step.M1;
            case "5m" -> Step.M5;
            case "1h" -> Step.H1;
            default -> throw new IllegalArgumentException("step must be auto, 1s, 10s, 1m, 5m, or 1h");
        };
    }

    static int points(long spanMillis, Step step) {
        return (int) Math.min(Integer.MAX_VALUE, (spanMillis + step.millis - 1) / step.millis);
    }

    private static Duration duration(String text) {
        String value = text.toLowerCase();
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2).trim()));
        }
        if (value.endsWith("d")) {
            return Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1).trim()));
        }
        if (value.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1).trim()));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1).trim()));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1).trim()));
        }
        throw new IllegalArgumentException("last must look like 15m, 4h, or 30s");
    }

    private static Instant instant(String text) {
        String value = text.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochMilli(Long.parseLong(value));
        }
        return Instant.parse(value);
    }

    public enum Step {
        S1("1s", 1_000L, 1_000_000_000L),
        S10("10s", 10_000L, 10_000_000_000L),
        M1("1m", 60_000L, 60_000_000_000L),
        M5("5m", 300_000L, 300_000_000_000L),
        H1("1h", 3_600_000L, 3_600_000_000_000L);

        public final String label;
        public final long millis;
        public final long nanos;

        Step(String label, long millis, long nanos) {
            this.label = label;
            this.millis = millis;
            this.nanos = nanos;
        }

        public String table() {
            return switch (this) {
                case S1 -> "rollup_1s";
                case S10 -> "rollup_10s";
                default -> "rollup_1m";
            };
        }
    }

    public record Range(Instant from, Instant to) {
        public long fromNs() {
            return from.getEpochSecond() * 1_000_000_000L + from.getNano();
        }

        public long toNs() {
            return to.getEpochSecond() * 1_000_000_000L + to.getNano();
        }
    }

    public record StepChoice(Step step, boolean coarserThanRequested) {}
}
