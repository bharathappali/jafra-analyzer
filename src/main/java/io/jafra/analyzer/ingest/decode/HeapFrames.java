package io.jafra.analyzer.ingest.decode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Joins per-GC heap summaries into one generation picture per collection. */
public final class HeapFrames {
    private HeapFrames() {}

    public static Result build(JfrChunkReader.ReadChunk chunk) {
        String young = chunk.gcConfig == null ? null : chunk.gcConfig.young();
        String old = chunk.gcConfig == null ? null : chunk.gcConfig.old();
        return build(chunk.gcs, young, old, chunk.newRatio, chunk.heapSummaries, chunk.generations, chunk.youngGcs, chunk.zCollections,
                chunk.regions);
    }

    static Result build(
            List<JfrChunkReader.GcRow> gcs,
            String youngCollector,
            String oldCollector,
            Long newRatio,
            List<HeapSummary> heaps,
            List<GenerationSummary> generations,
            List<YoungGc> youngGcs,
            List<ZCollection> zCollections,
            List<RegionEvent> regions) {
        Map<Long, Picture> before = new LinkedHashMap<>();
        Map<Long, Picture> after = new LinkedHashMap<>();
        for (HeapSummary summary : heaps) {
            picture(summary.before() ? before : after, summary.gcId()).heap = summary.heapUsed();
            picture(summary.before() ? before : after, summary.gcId()).committed = summary.committed();
        }
        for (GenerationSummary summary : generations) {
            Picture picture = picture(summary.before() ? before : after, summary.gcId());
            picture.eden = summary.edenUsed();
            picture.survivor = summary.survivorUsed();
            picture.old = summary.oldUsed();
            picture.exact = true;
        }
        Map<Long, Integer> tenuring = new LinkedHashMap<>();
        for (YoungGc young : youngGcs) {
            if (young.gcId() != null && young.tenuring() != null) {
                tenuring.put(young.gcId(), young.tenuring());
            }
        }
        List<RegionFrame> frames = frames(regions);
        Map<Long, Long> humongous = new LinkedHashMap<>();
        for (RegionFrame frame : frames) {
            if (frame.gcId() == null || frame.type() == null || !frame.type().toLowerCase(Locale.ROOT).contains("humongous")) {
                continue;
            }
            humongous.merge(frame.gcId(), frame.count(), Long::sum);
        }
        String collector = collector(youngCollector, oldCollector, generations);
        boolean serial = "Serial".equals(collector);
        List<Row> rows = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (JfrChunkReader.GcRow gc : gcs) {
            if (gc.gcId() != null) {
                seen.add(gc.gcId());
            }
            rows.add(row(gc.t(), gc.gcId(), gc.name(), gc.cause(), gc.kind(), collector, serial, newRatio,
                    before.get(gc.gcId()), after.get(gc.gcId()), tenuring.get(gc.gcId()), humongous.get(gc.gcId())));
        }
        for (ZCollection collection : zCollections) {
            if (collection.gcId() != null && seen.contains(collection.gcId())) {
                continue;
            }
            rows.add(row(collection.t(), collection.gcId(), collection.kind(), null, collection.kind(), "Z", false, null,
                    before.get(collection.gcId()), after.get(collection.gcId()), tenuring.get(collection.gcId()), null));
        }
        return new Result(rows, frames);
    }

    private static Row row(
            long t, Long gcId, String name, String cause, String kind, String collector, boolean serial, Long newRatio, Picture before,
            Picture after, Integer tenuring, Long humongous) {
        boolean exact = (before != null && before.exact) || (after != null && after.exact);
        Long committed = first(after == null ? null : after.committed, before == null ? null : before.committed);
        Long youngCapacity = null;
        boolean estimated = false;
        if (!exact && serial && committed != null && newRatio != null && newRatio >= 0) {
            youngCapacity = committed / (newRatio + 1);
            estimated = true;
        }
        Long oldBefore = usedOld(before);
        Long oldAfter = usedOld(after);
        Long promoted = oldBefore != null && oldAfter != null && oldAfter >= oldBefore ? oldAfter - oldBefore : null;
        return new Row(
                t,
                gcId,
                name,
                cause,
                kind,
                collector,
                estimated,
                before == null ? null : before.heap,
                after == null ? null : after.heap,
                committed,
                before == null ? null : before.eden,
                after == null ? null : after.eden,
                before == null ? null : before.survivor,
                after == null ? null : after.survivor,
                oldBefore,
                oldAfter,
                youngCapacity,
                humongous,
                promoted,
                tenuring);
    }

    private static Long usedOld(Picture picture) {
        if (picture == null) {
            return null;
        }
        if (picture.old != null) {
            return picture.old;
        }
        if (picture.exact && picture.heap != null && picture.eden != null && picture.survivor != null) {
            return picture.heap - picture.eden - picture.survivor;
        }
        return null;
    }

    private static Long first(Long preferred, Long fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static Picture picture(Map<Long, Picture> sides, Long gcId) {
        return sides.computeIfAbsent(gcId, ignored -> new Picture());
    }

    private static List<RegionFrame> frames(List<RegionEvent> regions) {
        Map<String, RegionFrame> grouped = new LinkedHashMap<>();
        for (RegionEvent region : regions) {
            if (region.type() == null) {
                continue;
            }
            String key = (region.gcId() == null ? "t" + region.t() : "g" + region.gcId()) + "|" + region.type();
            RegionFrame existing = grouped.get(key);
            if (existing == null) {
                grouped.put(key, new RegionFrame(region.t(), region.gcId(), region.type(), 1L, region.used()));
            } else {
                grouped.put(key, new RegionFrame(existing.t(), existing.gcId(), existing.type(), existing.count() + 1,
                        existing.used() + region.used()));
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private static String collector(String young, String old, List<GenerationSummary> generations) {
        for (GenerationSummary summary : generations) {
            if ("g1".equals(summary.source())) {
                return "G1";
            }
            if ("parallel".equals(summary.source())) {
                return "Parallel";
            }
        }
        String blob = ((young == null ? "" : young) + " " + (old == null ? "" : old)).toLowerCase(Locale.ROOT);
        if (blob.contains("g1")) {
            return "G1";
        }
        if (blob.contains("zgc") || blob.contains("z ")) {
            return "Z";
        }
        if (blob.contains("parallel") || blob.contains("ps")) {
            return "Parallel";
        }
        if (blob.contains("serial") || blob.contains("defnew")) {
            return "Serial";
        }
        return null;
    }

    private static final class Picture {
        private Long heap;
        private Long committed;
        private Long eden;
        private Long survivor;
        private Long old;
        private boolean exact;
    }

    public record HeapSummary(Long gcId, boolean before, Long heapUsed, Long committed) {}

    public record GenerationSummary(Long gcId, boolean before, String source, Long edenUsed, Long survivorUsed, Long oldUsed) {}

    public record YoungGc(Long gcId, Integer tenuring) {}

    public record ZCollection(long t, Long gcId, String kind) {}

    public record RegionEvent(long t, Long gcId, String type, long used) {}

    public record Row(
            long t,
            Long gcId,
            String name,
            String cause,
            String kind,
            String collector,
            boolean estimated,
            Long heapBefore,
            Long heapAfter,
            Long committed,
            Long edenBefore,
            Long edenAfter,
            Long survivorBefore,
            Long survivorAfter,
            Long oldBefore,
            Long oldAfter,
            Long youngCapacity,
            Long humongousRegions,
            Long promotedBytes,
            Integer tenuring) {}

    public record RegionFrame(long t, Long gcId, String type, long count, long used) {}

    public record Result(List<Row> collections, List<RegionFrame> regions) {}
}
