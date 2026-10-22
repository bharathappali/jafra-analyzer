package io.jafra.analyzer.v2.rollup.extract.internal;

import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.rollup.ChunkFacts;
import io.jafra.analyzer.v2.rollup.Seen;
import io.jafra.analyzer.v2.rollup.WindowData;

/** Internal inputs written in the same chunk pass as the catalogue extractors. */
public final class InternalExtractors {
    private InternalExtractors() {}

    public static void extract(ChunkFacts facts, WindowData out) {
        for (Seen phase : facts.ofType("jdk.GCPhasePauseLevel1")) {
            Double duration = phase.num("duration");
            Long micros = duration == null ? null : duration.longValue();
            String name = phase.text("name");
            String fields = "{\"gc_id\":" + phase.id("gcId") + ",\"phase\":" + quote(name) + "}";
            out.internalEvent(new WindowData.StoredEvent(
                    InternalInputs.GC_PHASE_L1, phase.startMs, micros, phase.thread, null, null, fields));
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
