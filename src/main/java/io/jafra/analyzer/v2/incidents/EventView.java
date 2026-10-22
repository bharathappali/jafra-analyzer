package io.jafra.analyzer.v2.incidents;

import java.util.List;

import io.jafra.analyzer.v2.store.V2Store.EventRow;

public interface EventView {
    List<EventRow> of(String type);

    /** This window's {@code internal_events} rows for one internal name. */
    List<EventRow> internal(String name);
}
