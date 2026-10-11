package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;

@ApplicationScoped
public class JfrBundleService {
    private static final int DEFAULT_LIMIT = 20;

    private static final Map<String, BundleDefinition> DEFINITIONS = Map.ofEntries(
            Map.entry(
                    "version-check",
                    new BundleDefinition(
                            "version-check",
                            "JVM version baseline required before every analysis_algo.md playbook")),
            Map.entry(
                    "memory",
                    new BundleDefinition(
                            "memory",
                            "Memory OOM and leak playbook: occupancy trends, allocations, and OOM-related events")),
            Map.entry(
                    "gc",
                    new BundleDefinition(
                            "gc",
                            "GC pause and latency playbook: configuration, pause stats, and heap trends")),
            Map.entry(
                    "threads",
                    new BundleDefinition(
                            "threads",
                            "Thread exhaustion and contention playbook: parks, monitors, and wall-clock samples")),
            Map.entry(
                    "performance",
                    new BundleDefinition(
                            "performance",
                            "Application performance playbook: CPU samples, allocations, and JVM context")),
            Map.entry(
                    "jvm-config",
                    new BundleDefinition(
                            "jvm-config",
                            "JVM configuration playbook: flags and GC/heap configuration events")),
            Map.entry(
                    "exceptions",
                    new BundleDefinition(
                            "exceptions",
                            "Exception handling playbook: thrown exceptions and statistics")),
            Map.entry(
                    "diagnostics",
                    new BundleDefinition(
                            "diagnostics",
                            "Diagnostic evidence playbook: dump flags and DumpReason events")),
            Map.entry(
                    "proactive",
                    new BundleDefinition(
                            "proactive",
                            "Proactive alerting playbook: trends, GC pauses, and allocation rate")));

    @Inject
    JfrCollectService collect;

    @Inject
    JfrAggregationService aggregations;

    public BundleCatalogDocument catalog() {
        List<BundleSummary> bundles = DEFINITIONS.values().stream()
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .map(def -> new BundleSummary(def.id(), def.description()))
                .toList();
        return new BundleCatalogDocument(bundles);
    }

    public BundleDocument bundle(RecordingCatalog.WindowSelection selection, String id, BundleParams params)
            throws IOException {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        BundleDefinition definition = DEFINITIONS.get(normalized);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "bundle id must be one of: " + String.join(", ", DEFINITIONS.keySet()));
        }
        int limit = params.limit() == null ? DEFAULT_LIMIT : params.limit();
        Map<String, Object> sections = new LinkedHashMap<>();
        switch (normalized) {
            case "version-check" -> sections.put("jvm", strip(collect.jvm(selection)));
            case "memory" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put("trends", strip(aggregations.aggregate(selection, "trends", limit, null, null)));
                sections.put("allocations", strip(aggregations.aggregate(selection, "allocations", limit, null, null)));
                sections.put(
                        "oomEvents",
                        safeEventSummary(
                                selection,
                                "JavaErrorThrow,DumpReason,MetaspaceOOM,MetaspaceAllocationFailure"));
            }
            case "gc" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put("gcConfiguration", safeEventSummary(selection, "GCConfiguration,GCHeapConfiguration"));
                sections.put("gcPauses", strip(aggregations.aggregate(selection, "gc-pauses", limit, null, null)));
                sections.put("trends", strip(aggregations.aggregate(selection, "trends", limit, null, null)));
            }
            case "threads" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put(
                        "contention",
                        strip(aggregations.aggregate(
                                selection, "contention", limit, params.minDuration(), null)));
                sections.put(
                        "wallSamples",
                        strip(aggregations.aggregate(selection, "samples", limit, null, "wall")));
            }
            case "performance" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                String flavor = params.flavor() == null || params.flavor().isBlank() ? "cpu" : params.flavor();
                sections.put(
                        "samples",
                        strip(aggregations.aggregate(selection, "samples", limit, null, flavor)));
                sections.put("allocations", strip(aggregations.aggregate(selection, "allocations", limit, null, null)));
            }
            case "jvm-config" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put("flags", strip(collect.jvmFlags(selection, params.flagNames())));
                sections.put("gcConfiguration", safeEventSummary(selection, "GCConfiguration,GCHeapConfiguration"));
            }
            case "exceptions" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put("exceptions", strip(aggregations.aggregate(selection, "exceptions", limit, null, null)));
                sections.put("exceptionStatistics", safeEventSummary(selection, "ExceptionStatistics"));
            }
            case "diagnostics" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put(
                        "flags",
                        strip(collect.jvmFlags(
                                selection,
                                "HeapDumpOnOutOfMemoryError,HeapDumpPath,MaxHeapSize,MaxMetaspaceSize")));
                sections.put("dumpReason", safeEventSummary(selection, "DumpReason"));
                sections.put(
                        "container",
                        safeEventSummary(selection, "ContainerMemoryUsage,ContainerConfiguration"));
            }
            case "proactive" -> {
                sections.put("jvm", strip(collect.jvm(selection)));
                sections.put("trends", strip(aggregations.aggregate(selection, "trends", limit, null, null)));
                sections.put("gcPauses", strip(aggregations.aggregate(selection, "gc-pauses", limit, null, null)));
                sections.put("allocations", strip(aggregations.aggregate(selection, "allocations", limit, null, null)));
            }
            default -> throw new IllegalArgumentException("unsupported bundle: " + id);
        }
        return new BundleDocument(AnalysisEnvelope.from(selection), definition.id(), definition.description(), sections);
    }

    private Object safeEventSummary(RecordingCatalog.WindowSelection selection, String types) throws IOException {
        try {
            return strip(collect.events(selection, types, "summary", null, null, null));
        } catch (IllegalArgumentException ignored) {
            return Map.of("view", "summary", "summary", Map.of(), "typesRequested", types);
        }
    }

    private static Object strip(JfrCollectService.JvmDocument document) {
        return document.jvm();
    }

    private static Object strip(JfrCollectService.JvmFlagsDocument document) {
        return document.flags();
    }

    private static Object strip(JfrCollectService.EventsDocument document) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("view", document.view());
        if (document.field() != null) {
            map.put("field", document.field());
        }
        if (document.summary() != null) {
            map.put("summary", document.summary());
        }
        if (document.records() != null) {
            map.put("records", document.records());
        }
        if (document.series() != null) {
            map.put("series", document.series());
        }
        return map;
    }

    private static Object strip(JfrAggregationService.AggregationDocument document) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", document.kind());
        if (document.stats() != null) {
            map.put("stats", document.stats());
        }
        if (document.trends() != null && !document.trends().isEmpty()) {
            map.put("trends", document.trends());
        }
        if (document.top() != null && !document.top().isEmpty()) {
            map.put("top", document.top());
        }
        return map;
    }

    public record BundleParams(Integer limit, String minDuration, String flavor, String flagNames) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BundleCatalogDocument(List<BundleSummary> bundles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BundleSummary(String id, String description) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BundleDocument(
            AnalysisEnvelope context, String id, String description, Map<String, Object> sections) {}

    private record BundleDefinition(String id, String description) {}
}
