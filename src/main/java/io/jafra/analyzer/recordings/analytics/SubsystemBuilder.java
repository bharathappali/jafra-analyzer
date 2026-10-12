package io.jafra.analyzer.recordings.analytics;

import java.util.List;

import org.openjdk.jmc.common.item.IItemCollection;

import io.jafra.analyzer.recordings.TimeBucketGrid;

public interface SubsystemBuilder {
    String id();

    AnalyticsModels.SubsystemResult build(
            IItemCollection events,
            java.util.Set<String> available,
            TimeBucketGrid grid,
            AnalyticsModels.AnalyticsParams params,
            List<AnalyticsModels.Marker> markers);
}
