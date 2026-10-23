package io.jafra.analyzer.v2.rollup.extract.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jafra.analyzer.v2.incidents.support.InternalInputs;
import io.jafra.analyzer.v2.rollup.ChunkFacts;
import io.jafra.analyzer.v2.rollup.Frames;
import io.jafra.analyzer.v2.rollup.Seen;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.rollup.extract.Extractors;

/** Internal inputs written in the same chunk pass as the catalogue extractors. */
public final class InternalExtractors {
    private InternalExtractors() {}

    public static void extract(ChunkFacts facts, WindowData out) {
        phases(facts, out);
        eventTypes(facts, out);
        directMax(facts, out);
        loaders(facts, out);
        stalls(facts, out);
        redefine(facts, out);
        methodTiming(facts, out);
        malloc(facts, out);
    }

    private static void phases(ChunkFacts facts, WindowData out) {
        for (Seen phase : facts.ofType("jdk.GCPhasePauseLevel1")) {
            Double duration = phase.num("duration");
            Long micros = duration == null ? null : duration.longValue();
            String name = phase.text("name");
            String fields = "{\"gc_id\":" + phase.id("gcId") + ",\"phase\":" + quote(name) + "}";
            out.internalEvent(new WindowData.StoredEvent(
                    InternalInputs.GC_PHASE_L1, phase.startMs, micros, phase.thread, null, null, fields));
        }
    }

    private static void eventTypes(ChunkFacts facts, WindowData out) {
        for (String type : facts.eventTypes) {
            out.internalSeries(InternalInputs.CHUNK_EVENT_TYPES, type, 1d);
        }
    }

    private static void directMax(ChunkFacts facts, WindowData out) {
        Seen direct = null;
        for (Seen event : facts.ofType("jdk.DirectBufferStatistics")) {
            direct = event;
        }
        if (direct == null) {
            return;
        }
        Double max = direct.num("maxCapacity");
        if (max != null) {
            out.internalSeries(InternalInputs.DIRECT_MAX_BYTES, "", max);
        }
    }

    private static void loaders(ChunkFacts facts, WindowData out) {
        List<Seen> loaders = facts.ofType("jdk.ClassLoaderStatistics");
        if (loaders.isEmpty()) {
            return;
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Double> classes = new LinkedHashMap<>();
        Map<String, Double> bytes = new LinkedHashMap<>();
        Map<String, Double> previous = new LinkedHashMap<>();
        out.internalSeries(InternalInputs.LOADER_COUNT, "", loaders.size());
        for (Seen loader : loaders) {
            String type = first(loader, "classLoader.type.name", "classLoader");
            String data = first(loader, "classLoaderData", "classLoader");
            String label = first(loader, "classLoader.name", "classLoader.type.name", "classLoader");
            byType.merge(type == null ? "unknown" : type, 1, Integer::sum);
            Double classCount = loader.num("classCount");
            Double chunk = loader.num("chunkSize");
            if (classCount != null) {
                classes.merge(type == null ? "unknown" : type, classCount, Double::sum);
            }
            if (chunk != null) {
                bytes.merge(type == null ? "unknown" : type, chunk, Double::sum);
            }
            String key = "loader=" + (label == null ? "unknown" : label) + "@" + (data == null ? "" : data);
            double prior = previous.getOrDefault(data == null ? key : data, classCount == null ? 0d : classCount);
            if (classCount != null && data != null && previous.containsKey(data)) {
                out.internalSeries(InternalInputs.LOADER_CLASS_DELTA, key, classCount - prior);
            }
            if (data != null && classCount != null) {
                previous.put(data, classCount);
            }
        }
        byType.forEach((type, count) -> out.internalSeries(InternalInputs.LOADER_COUNT, "type=" + type, count));
        classes.forEach((type, count) -> out.internalSeries(InternalInputs.LOADER_TYPE_CLASSES, "type=" + type, count));
        bytes.forEach((type, count) -> out.internalSeries(InternalInputs.LOADER_TYPE_CHUNK_BYTES, "type=" + type, count));
    }

    private static void stalls(ChunkFacts facts, WindowData out) {
        for (Seen stall : facts.ofType("jdk.ZAllocationStall")) {
            Double duration = stall.num("duration");
            long stack = Extractors.remember(out, stall.frames);
            String fields = "{\"type\":" + quote(stall.text("type")) + ",\"size\":" + number(stall.num("size"))
                    + ",\"top_frame\":" + quote(Frames.top(stall.frames)) + ",\"stack_id\":" + stack + "}";
            out.internalEvent(new WindowData.StoredEvent(
                    InternalInputs.ZGC_STALL,
                    stall.startMs,
                    duration == null ? null : duration.longValue(),
                    stall.thread,
                    null,
                    stack,
                    fields));
        }
    }

    private static void redefine(ChunkFacts facts, WindowData out) {
        emitRedefine(out, facts.ofType("jdk.RedefineClasses"), "redefine");
        emitRedefine(out, facts.ofType("jdk.RetransformClasses"), "retransform");
    }

    private static void emitRedefine(WindowData out, List<Seen> events, String kind) {
        for (Seen event : events) {
            Double duration = event.num("duration");
            long stack = Extractors.remember(out, event.frames);
            String fields = "{\"kind\":" + quote(kind) + ",\"classCount\":" + number(event.num("classCount"))
                    + ",\"redefinitionId\":" + number(event.num("redefinitionId")) + "}";
            out.internalEvent(new WindowData.StoredEvent(
                    InternalInputs.REDEFINE,
                    event.startMs,
                    duration == null ? null : duration.longValue(),
                    event.thread,
                    null,
                    stack,
                    fields));
        }
    }

    private static void methodTiming(ChunkFacts facts, WindowData out) {
        Map<String, Seen> last = new LinkedHashMap<>();
        for (Seen timing : facts.ofType("jdk.MethodTiming")) {
            String handler = handler(timing);
            if (handler != null) {
                last.put(handler, timing);
            }
        }
        for (Map.Entry<String, Seen> entry : last.entrySet()) {
            String key = "handler=" + entry.getKey();
            Seen timing = entry.getValue();
            put(out, InternalInputs.METHOD_TIMING_RAW, key + ";field=invocations", timing.num("invocations"));
            put(out, InternalInputs.METHOD_TIMING_RAW, key + ";field=average", timing.num("average"));
            put(out, InternalInputs.METHOD_TIMING_RAW, key + ";field=maximum", timing.num("maximum"));
            put(out, InternalInputs.METHOD_TIMING_RAW, key + ";field=minimum", timing.num("minimum"));
        }
    }

    private static void malloc(ChunkFacts facts, WindowData out) {
        for (Seen malloc : facts.ofType("profiler.Malloc")) {
            Double size = malloc.num("size");
            if (size == null || size <= 0d) {
                continue;
            }
            long stack = Extractors.remember(out, malloc.frames);
            out.sample("malloc", stack, malloc.thread, "", "", 1, size);
        }
    }

    private static void put(WindowData out, String name, String key, Double value) {
        if (value != null) {
            out.internalSeries(name, key, value);
        }
    }

    private static String handler(Seen timing) {
        String method = timing.text("method");
        if (method != null) {
            return method;
        }
        String type = first(timing, "type.name", "type");
        String name = timing.text("name");
        if (type == null || name == null) {
            return null;
        }
        return type + "." + name;
    }

    private static String first(Seen event, String... names) {
        for (String name : names) {
            String text = event.text(name);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String number(Double value) {
        return value == null ? "null" : Double.toString(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
