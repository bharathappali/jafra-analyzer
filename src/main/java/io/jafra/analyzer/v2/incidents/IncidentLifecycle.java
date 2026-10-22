package io.jafra.analyzer.v2.incidents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Open and close rules. An UNKNOWN window does not occupy a slot. "Last 3 windows" means the last 3
 * non-UNKNOWN windows of this (jvm, type). The close streak counts consecutive OK windows and skips
 * UNKNOWN: UNKNOWN neither breaks nor extends it. So bad, UNKNOWN, good, bad opens (2 of the last 3
 * non-UNKNOWN), and good, good, UNKNOWN, good closes.
 *
 * <p>incident_windows records every window from t0 through the last bad window, including the good
 * windows in between. A bad window is written at once. Good windows are held and written when the
 * next bad window arrives, and dropped on close. No recorded window has ts &gt;= t1.
 */
public final class IncidentLifecycle {
    public enum Kind {
        INSTANT,
        RATE_LEVEL
    }

    public enum State {
        OK,
        WARNING,
        CRITICAL,
        UNKNOWN
    }

    public enum Action {
        NONE,
        OPEN,
        UPDATE,
        CLOSE
    }

    private final ArrayDeque<Mark> trail = new ArrayDeque<>();
    private final List<Mark> held = new ArrayList<>();
    private final List<Long> recorded = new ArrayList<>();
    private List<Long> stepRecorded = List.of();
    private Open open;
    private Open lastClosed;
    private int goodStreak;
    private long lastBadTo = -1;

    public Action accept(Kind kind, Mark mark) {
        stepRecorded = List.of();
        trail.addLast(mark);
        trim();
        if (mark.state() == State.OK) {
            goodStreak++;
        } else if (isBad(mark)) {
            goodStreak = 0;
            lastBadTo = mark.toMs();
        }
        if (open == null) {
            if (!opens(kind)) {
                return Action.NONE;
            }
            long t0 = openingT0();
            open = new Open(t0, worstSince(t0), mark);
            goodStreak = 0;
            remember(from(t0));
            return Action.OPEN;
        }
        if (goodStreak >= 3) {
            held.clear();
            lastClosed = open;
            open = null;
            goodStreak = 0;
            return Action.CLOSE;
        }
        if (mark.state() == State.UNKNOWN) {
            held.add(mark);
            return Action.NONE;
        }
        if (isBad(mark)) {
            List<Long> write = new ArrayList<>();
            for (Mark waiting : held) {
                write.add(waiting.fromMs());
            }
            held.clear();
            write.add(mark.fromMs());
            remember(write);
            note(mark);
            return Action.UPDATE;
        }
        held.add(mark);
        note(mark);
        return Action.UPDATE;
    }

    /** Timestamps written by the latest {@link #accept}. */
    public List<Long> recordedThisStep() {
        return stepRecorded;
    }

    /** Every incident_windows timestamp this lifecycle has asked to store. */
    public List<Long> recordedWindows() {
        return List.copyOf(recorded);
    }

    public Open open() {
        return open;
    }

    public Open lastClosed() {
        return lastClosed;
    }

    public long lastBadTo() {
        return lastBadTo;
    }

    public int goodStreak() {
        return goodStreak;
    }

    /**
     * Puts an incident back after a restart. {@code streak} is the number of processed windows after
     * the last bad window.
     */
    public void restore(long t0, Mark worst, Mark latest, int streak, long badTo) {
        open = new Open(t0, worst, latest);
        goodStreak = streak;
        lastBadTo = badTo;
        held.clear();
    }

    /**
     * Both bounds: 60 at warning, 90 at critical, linear between, linear above critical up to 100 at
     * 2× critical. Below warning: 60·value/warning, or 0 when warning ≤ 0. Warning-only and
     * critical-only bands are scored here too (step 1 uses the same method).
     */
    public static double score(double value, Band band) {
        if (band == null || Double.isNaN(value)) {
            return 0d;
        }
        Double warning = band.warning();
        Double critical = band.critical();
        if (warning != null && critical != null) {
            if (value <= warning) {
                return warning <= 0d ? 0d : Math.min(60d, 60d * value / warning);
            }
            if (value >= critical) {
                return above(value, critical);
            }
            return 60d + 30d * (value - warning) / (critical - warning);
        }
        if (warning != null) {
            if (warning <= 0d) {
                return 0d;
            }
            if (value <= warning) {
                return Math.min(60d, 60d * value / warning);
            }
            return Math.min(89d, 60d + 29d * (value - warning) / warning);
        }
        if (critical != null) {
            if (value < critical) {
                return critical <= 0d ? 0d : Math.min(90d, 90d * value / critical);
            }
            return above(value, critical);
        }
        return 0d;
    }

    private static double above(double value, double critical) {
        if (critical <= 0d) {
            return 100d;
        }
        return Math.min(100d, 90d + 10d * (value - critical) / critical);
    }

    public static String confidence(double score, boolean related) {
        if (score >= 90d && related) {
            return "strong";
        }
        if (score >= 60d) {
            return "medium";
        }
        return "weak";
    }

    private void remember(List<Long> timestamps) {
        recorded.addAll(timestamps);
        stepRecorded = List.copyOf(timestamps);
    }

    private List<Long> from(long t0) {
        List<Long> timestamps = new ArrayList<>();
        for (Mark mark : trail) {
            if (mark.fromMs() >= t0) {
                timestamps.add(mark.fromMs());
            }
        }
        return timestamps;
    }

    private void note(Mark mark) {
        open.latest = mark;
        if (mark.score() > open.worst.score()
                || (mark.score() == open.worst.score() && mark.fromMs() >= open.worst.fromMs())) {
            open.worst = mark;
            open.peakAt = mark.fromMs();
        }
    }

    private Mark worstSince(long t0) {
        Mark worst = null;
        for (Mark mark : trail) {
            if (mark.fromMs() < t0 || !isBad(mark)) {
                continue;
            }
            if (worst == null
                    || mark.score() > worst.score()
                    || (mark.score() == worst.score() && mark.fromMs() >= worst.fromMs())) {
                worst = mark;
            }
        }
        return worst == null ? trail.peekLast() : worst;
    }

    private void trim() {
        List<Mark> decided = new ArrayList<>();
        for (Mark mark : trail) {
            if (mark.state() != State.UNKNOWN) {
                decided.add(mark);
            }
        }
        if (decided.size() <= 3) {
            return;
        }
        long keepFrom = decided.get(decided.size() - 3).fromMs();
        while (!trail.isEmpty() && trail.peekFirst().fromMs() < keepFrom) {
            trail.removeFirst();
        }
    }

    private boolean opens(Kind kind) {
        Mark last = trail.peekLast();
        if (last == null || !isBad(last)) {
            return false;
        }
        if (kind == Kind.INSTANT) {
            return true;
        }
        int bad = 0;
        for (Mark mark : lastDecided()) {
            if (isBad(mark)) {
                bad++;
            }
        }
        return bad >= 2;
    }

    private long openingT0() {
        for (Mark mark : lastDecided()) {
            if (isBad(mark)) {
                return mark.fromMs();
            }
        }
        return trail.peekLast().fromMs();
    }

    private List<Mark> lastDecided() {
        List<Mark> decided = new ArrayList<>();
        for (Mark mark : trail) {
            if (mark.state() != State.UNKNOWN) {
                decided.add(mark);
            }
        }
        if (decided.size() <= 3) {
            return decided;
        }
        return decided.subList(decided.size() - 3, decided.size());
    }

    private static boolean isBad(Mark mark) {
        return mark.state() == State.WARNING || mark.state() == State.CRITICAL;
    }

    public static final class Mark {
        private final long fromMs;
        private final long toMs;
        private final WindowVerdict verdict;
        private final double score;

        public Mark(long fromMs, long toMs, WindowVerdict verdict, double score) {
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.verdict = verdict;
            this.score = score;
        }

        public Mark(long fromMs, long toMs, State state, double value, double score, String headline) {
            this(
                    fromMs,
                    toMs,
                    new WindowVerdict(
                            state,
                            value,
                            "",
                            null,
                            new Band(null, null),
                            "",
                            "",
                            null,
                            List.of(),
                            List.of(),
                            "",
                            headline,
                            List.of(),
                            java.util.Set.of(),
                            null),
                    score);
        }

        public long fromMs() {
            return fromMs;
        }

        public long toMs() {
            return toMs;
        }

        public State state() {
            return verdict.state();
        }

        public double value() {
            return verdict.value() == null ? Double.NaN : verdict.value();
        }

        public double score() {
            return score;
        }

        public String headline() {
            return verdict.headline();
        }

        public WindowVerdict verdict() {
            return verdict;
        }
    }

    public static final class Open {
        private final long t0;
        private Mark latest;
        private Mark worst;
        private long peakAt;

        Open(long t0, Mark worst, Mark latest) {
            this.t0 = t0;
            this.worst = worst;
            this.latest = latest;
            this.peakAt = worst.fromMs();
        }

        public long t0() {
            return t0;
        }

        public Mark latest() {
            return latest;
        }

        public Mark worst() {
            return worst;
        }

        public long peakAt() {
            return peakAt;
        }
    }
}
