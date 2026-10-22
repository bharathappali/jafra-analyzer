package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jafra.analyzer.v2.store.V2Store.RollupRow;

public interface RollupView {
    /** This window's total row ({@code by_name} and {@code key} empty). */
    Optional<RollupRow> total(String metric);

    /** This window's breakdown rows for {@code by}, highest sum first. */
    List<RollupRow> breakdown(String metric, String by);

    /** This window's histogram buckets. {@code key} empty is the total. */
    Map<Integer, Long> hist(String metric, String key);

    /** Newest total row of this JVM with {@code ts} before the window, any age. */
    Optional<RollupRow> lastBefore(String metric);

    /**
     * Breakdown of {@code metric} summed over this window and the {@code windows - 1}
     * processed windows before it. The default is this window only.
     */
    default List<RollupRow> recentBreakdown(String metric, String by, int windows) {
        return breakdown(metric, by);
    }
}
