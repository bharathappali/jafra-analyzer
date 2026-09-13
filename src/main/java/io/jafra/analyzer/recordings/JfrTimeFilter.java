package io.jafra.analyzer.recordings;

import java.time.Instant;

import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemFilter;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;

/** Restrict JMC event collections to a wall-clock analysis window. */
final class JfrTimeFilter {
    private JfrTimeFilter() {}

    static IItemCollection apply(IItemCollection events, Instant from, Instant to) {
        if (events == null || (from == null && to == null)) {
            return events;
        }
        IItemFilter filter;
        if (from != null && to != null) {
            filter = ItemFilters.interval(
                    JfrAttributes.START_TIME, quantity(from), true, quantity(to), false);
        } else if (from != null) {
            filter = ItemFilters.moreOrEqual(JfrAttributes.START_TIME, quantity(from));
        } else {
            filter = ItemFilters.less(JfrAttributes.START_TIME, quantity(to));
        }
        return events.apply(filter);
    }

    private static IQuantity quantity(Instant instant) {
        long nanos = Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L) + instant.getNano();
        return UnitLookup.EPOCH_NS.quantity(nanos);
    }
}
