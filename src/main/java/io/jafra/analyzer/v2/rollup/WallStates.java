package io.jafra.analyzer.v2.rollup;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

/** Maps profiler.WallClockSample state strings. Newly seen values are logged once. */
public final class WallStates {
    private static final Logger LOG = Logger.getLogger(WallStates.class);
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private WallStates() {}

    public static String classify(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (SEEN.add(value)) {
            LOG.infof("profiler.WallClockSample state=%s", value);
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.equals("STATE_RUNNABLE") || upper.equals("RUNNABLE") || upper.endsWith(".RUNNABLE")) {
            return "running";
        }
        return "waiting";
    }
}
