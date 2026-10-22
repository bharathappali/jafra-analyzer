package io.jafra.analyzer.v2.incidents;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Proposal bands, pending KUSMA. Keys match jafra.v2.detect.* in application.properties. */
@ConfigMapping(prefix = "jafra.v2.detect")
public interface DetectorBands {
    LongPause longPause();

    HeapNearFull heapNearFull();

    ContainerNearLimit containerNearLimit();

    GcOverhead gcOverhead();

    FullGc fullGc();

    ZgcAllocStall zgcAllocStall();

    interface LongPause {
        @WithDefault("200")
        double warningMs();

        @WithDefault("1000")
        double criticalMs();
    }

    interface HeapNearFull {
        @WithDefault("85")
        double warningPct();

        @WithDefault("95")
        double criticalPct();
    }

    interface ContainerNearLimit {
        @WithDefault("90")
        double warningPct();
    }

    interface GcOverhead {
        @WithDefault("10")
        double warningPct();

        @WithDefault("25")
        double criticalPct();
    }

    interface FullGc {
        @WithDefault("3")
        int criticalCount();

        @WithDefault("12")
        int windowCount();
    }

    interface ZgcAllocStall {
        @WithDefault("1")
        double criticalPct();
    }
}
