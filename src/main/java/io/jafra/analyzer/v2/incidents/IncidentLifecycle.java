package io.jafra.analyzer.v2.incidents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Open and close rules. UNKNOWN counts as neither a good nor a bad window. That treatment is a
 * proposal, pending KUSMA.
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

    private final ArrayDeque<Mark> recent = new ArrayDeque<>();
    private Open open;
    private Open lastClosed;
    private int goodStreak;
    private long lastBadTo = -1;

    public Action accept(Kind kind, Mark mark) {
        recent.addLast(mark);
        while (recent.size() > 3) {
            recent.removeFirst();
        }
        if (mark.state() == State.OK) {
            goodStreak++;
        } else if (mark.state() == State.WARNING || mark.state() == State.CRITICAL) {
            goodStreak = 0;
            lastBadTo = mark.toMs();
        }
        if (open == null) {
            if (!opens(kind)) {
                return Action.NONE;
            }
            open = new Open(openingT0(), mark);
            goodStreak = 0;
            return Action.OPEN;
        }
        if (goodStreak >= 3) {
            lastClosed = open;
            open = null;
            goodStreak = 0;
            return Action.CLOSE;
        }
        if (mark.state() == State.UNKNOWN) {
            return Action.NONE;
        }
        open.latest = mark;
        if (mark.score() > open.worst.score() || (mark.score() == open.worst.score() && mark.fromMs() >= open.worst.fromMs())) {
            open.worst = mark;
            open.peakAt = mark.fromMs();
        }
        return Action.UPDATE;
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

    public List<Mark> recent() {
        return List.copyOf(recent);
    }

    private boolean opens(Kind kind) {
        int bad = 0;
        for (Mark mark : recent) {
            if (mark.state() == State.WARNING || mark.state() == State.CRITICAL) {
                bad++;
            }
        }
        if (kind == Kind.INSTANT) {
            Mark last = recent.peekLast();
            return last != null && (last.state() == State.WARNING || last.state() == State.CRITICAL);
        }
        return bad >= 2;
    }

    private long openingT0() {
        for (Mark mark : recent) {
            if (mark.state() == State.WARNING || mark.state() == State.CRITICAL) {
                return mark.fromMs();
            }
        }
        return recent.peekLast().fromMs();
    }

    public static double score(double value, double warning, double critical) {
        double high = critical <= warning ? warning + 1d : critical;
        if (value <= warning) {
            return warning == 0d ? 0d : Math.min(60d, 60d * value / warning);
        }
        if (value >= high) {
            return Math.min(100d, 90d + 10d * (value - high) / high);
        }
        return 60d + 30d * (value - warning) / (high - warning);
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

    public static final class Mark {
        private final long fromMs;
        private final long toMs;
        private final State state;
        private final double value;
        private final double score;
        private final String headline;

        public Mark(long fromMs, long toMs, State state, double value, double score, String headline) {
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.state = state;
            this.value = value;
            this.score = score;
            this.headline = headline;
        }

        public long fromMs() {
            return fromMs;
        }

        public long toMs() {
            return toMs;
        }

        public State state() {
            return state;
        }

        public double value() {
            return value;
        }

        public double score() {
            return score;
        }

        public String headline() {
            return headline;
        }
    }

    public static final class Open {
        private final long t0;
        private Mark latest;
        private Mark worst;
        private long peakAt;

        Open(long t0, Mark mark) {
            this.t0 = t0;
            this.latest = mark;
            this.worst = mark;
            this.peakAt = mark.fromMs();
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
