package io.jafra.analyzer.v2.incidents;

import java.util.List;

import io.jafra.analyzer.v2.rollup.Frame;
import io.jafra.analyzer.v2.store.V2Store.SampleRow;

public interface SampleView {
    List<SampleRow> rows(String kind);

    /** This window plus the {@code windows - 1} processed windows before it. */
    List<SampleRow> recent(String kind, int windows);

    /** Stack frames, callee first. */
    List<Frame> stack(long stackId);
}
