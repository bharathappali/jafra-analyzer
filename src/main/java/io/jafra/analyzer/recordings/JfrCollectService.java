package io.jafra.analyzer.recordings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;

@ApplicationScoped
public class JfrCollectService {
    private static final int DEFAULT_RECORD_LIMIT = 50;
    private static final int MAX_RECORD_LIMIT = 500;
    private static final int DEFAULT_SERIES_LIMIT = 500;
    private static final int MAX_SERIES_LIMIT = 5000;
    private static final Set<String> FLAG_SUFFIXES = Set.of(
            "BooleanFlag",
            "IntFlag",
            "LongFlag",
            "DoubleFlag",
            "StringFlag",
            "UnsignedIntFlag",
            "UnsignedLongFlag",
            "BooleanFlagChanged",
            "IntFlagChanged",
            "LongFlagChanged",
            "DoubleFlagChanged",
            "StringFlagChanged",
            "UnsignedIntFlagChanged",
            "UnsignedLongFlagChanged");

    @Inject
    JfrEventSummarizer summarizer;

    public EventTypesDocument eventTypes(RecordingCatalog.WindowSelection selection) throws IOException {
        return new EventTypesDocument(AnalysisEnvelope.from(selection), summarizer.listEventTypes(selection));
    }

    public EventsDocument events(
            RecordingCatalog.WindowSelection selection,
            String types,
            String view,
            String field,
            String minDuration,
            Integer limit)
            throws IOException {
        IItemCollection events = JfrEventSummarizer.loadEvents(selection);
        Set<String> available = JfrEventSummarizer.discoverTypes(events).keySet();
        List<String> resolved = JfrTypeResolver.resolve(JfrTypeResolver.splitTypesParam(types), available);
        String mode = view == null || view.isBlank() ? "summary" : view.trim().toLowerCase(Locale.ROOT);
        Long minDurationNs = JfrDuration.parseNanos(minDuration);
        return switch (mode) {
            case "summary" -> summaryCollect(selection, resolved);
            case "records" -> recordsCollect(selection, events, resolved, minDurationNs, limit);
            case "series" -> seriesCollect(selection, events, resolved, field, limit);
            default -> throw new IllegalArgumentException("view must be summary, records, or series");
        };
    }

    public JvmDocument jvm(RecordingCatalog.WindowSelection selection) throws IOException {
        IItemCollection events = JfrEventSummarizer.loadEvents(selection);
        List<Map<String, Object>> rows = extractRecords(events, "jdk.JVMInformation", null, 1);
        return new JvmDocument(AnalysisEnvelope.from(selection), rows.isEmpty() ? null : rows.getFirst());
    }

    public JvmFlagsDocument jvmFlags(RecordingCatalog.WindowSelection selection, String names) throws IOException {
        IItemCollection events = JfrEventSummarizer.loadEvents(selection);
        Map<String, FlagValue> merged = mergeFlags(events);
        if (names != null && !names.isBlank()) {
            Set<String> wanted = new LinkedHashSet<>();
            for (String piece : names.split(",")) {
                if (!piece.isBlank()) {
                    wanted.add(piece.trim());
                }
            }
            merged.keySet().retainAll(wanted);
        }
        return new JvmFlagsDocument(AnalysisEnvelope.from(selection), Map.copyOf(merged));
    }

    private EventsDocument summaryCollect(RecordingCatalog.WindowSelection selection, List<String> typeIds)
            throws IOException {
        Map<String, JfrEventSummarizer.EventSummary> events =
                summarizer.summarizeTypes(selection, new LinkedHashSet<>(typeIds));
        return new EventsDocument(AnalysisEnvelope.from(selection), "summary", null, events, null, null);
    }

    private EventsDocument recordsCollect(
            RecordingCatalog.WindowSelection selection,
            IItemCollection events,
            List<String> typeIds,
            Long minDurationNs,
            Integer limit)
            throws IOException {
        int cap = clamp(limit, DEFAULT_RECORD_LIMIT, MAX_RECORD_LIMIT);
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (String typeId : typeIds) {
            byType.put(typeId, extractRecords(events, typeId, minDurationNs, cap));
        }
        return new EventsDocument(AnalysisEnvelope.from(selection), "records", null, null, byType, null);
    }

    private EventsDocument seriesCollect(
            RecordingCatalog.WindowSelection selection,
            IItemCollection events,
            List<String> typeIds,
            String field,
            Integer limit)
            throws IOException {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is required for view=series");
        }
        if (typeIds.size() != 1) {
            throw new IllegalArgumentException("view=series supports exactly one type");
        }
        int cap = clamp(limit, DEFAULT_SERIES_LIMIT, MAX_SERIES_LIMIT);
        String typeId = typeIds.getFirst();
        List<SeriesPoint> points = extractSeries(events, typeId, field.trim(), cap);
        return new EventsDocument(
                AnalysisEnvelope.from(selection),
                "series",
                field.trim(),
                null,
                null,
                Map.of(typeId, points));
    }

    private static int clamp(Integer limit, int defaultValue, int max) {
        if (limit == null) {
            return defaultValue;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > max) {
            throw new IllegalArgumentException("limit exceeds maximum of " + max);
        }
        return limit;
    }

    private static List<Map<String, Object>> extractRecords(
            IItemCollection events, String typeId, Long minDurationNs, int limit) {
        IItemCollection typed = events.apply(ItemFilters.type(typeId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IItemIterable iterable : typed) {
            for (IItem item : iterable) {
                if (rows.size() >= limit) {
                    return rows;
                }
                if (minDurationNs != null && !meetsMinDuration(item, minDurationNs)) {
                    continue;
                }
                rows.add(itemToMap(item));
            }
        }
        return rows;
    }

    private static List<SeriesPoint> extractSeries(IItemCollection events, String typeId, String field, int limit) {
        IItemCollection typed = events.apply(ItemFilters.type(typeId));
        IAttribute<?> attribute = findAttribute(typed, field);
        if (attribute == null) {
            throw new IllegalArgumentException("field not found on type " + typeId + ": " + field);
        }
        List<SeriesPoint> points = new ArrayList<>();
        for (IItemIterable iterable : typed) {
            for (IItem item : iterable) {
                if (points.size() >= limit) {
                    return points;
                }
                IQuantity time = readQuantity(item, JfrAttributes.START_TIME);
                Number value = readNumber(item, attribute);
                if (time == null || value == null) {
                    continue;
                }
                try {
                    points.add(new SeriesPoint(time.longValueIn(UnitLookup.EPOCH_NS), value));
                } catch (QuantityConversionException ignored) {
                    // skip point
                }
            }
        }
        return points;
    }

    private static boolean meetsMinDuration(IItem item, long minDurationNs) {
        for (String name : List.of("duration", "parkTime", "blockedTime")) {
            IAttribute<?> attribute = findAttributeOnItem(item, name);
            if (attribute == null) {
                continue;
            }
            Number value = readNumber(item, attribute);
            if (value != null && value.longValue() >= minDurationNs) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> itemToMap(IItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        IType<?> type = item.getType();
        if (type == null) {
            return map;
        }
        for (IAttribute<?> attribute : type.getAttributes()) {
            if (attribute == null || "stackTrace".equals(attribute.getIdentifier())) {
                continue;
            }
            Object value = readValue(item, attribute);
            if (value != null) {
                map.put(attribute.getIdentifier(), value);
            }
        }
        return map;
    }

    private static Object readValue(IItem item, IAttribute<?> attribute) {
        IMemberAccessor<?, IItem> accessor = accessor(attribute, item.getType());
        if (accessor == null) {
            return null;
        }
        try {
            return jsonScalar(accessor.getMember(item));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Object jsonScalar(Object raw) {
        if (raw == null || raw instanceof IType<?>) {
            return null;
        }
        if (raw instanceof IQuantity quantity) {
            try {
                return quantity.longValue();
            } catch (RuntimeException ignored) {
                return quantity.toString();
            }
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof String text) {
            return text.length() <= 4000 ? text : text.substring(0, 4000);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equals(text) || text.startsWith("Type(")) {
            return null;
        }
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }

    private static Number readNumber(IItem item, IAttribute<?> attribute) {
        Object value = readValue(item, attribute);
        if (value instanceof Number number) {
            return number;
        }
        return null;
    }

    private static IQuantity readQuantity(IItem item, IAttribute<IQuantity> attribute) {
        IMemberAccessor<IQuantity, IItem> accessor = quantityAccessor(attribute, item.getType());
        if (accessor == null) {
            return null;
        }
        try {
            return accessor.getMember(item);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static IMemberAccessor<?, IItem> accessor(IAttribute<?> attribute, IType<?> type) {
        try {
            return attribute.getAccessor((IType<IItem>) type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static IMemberAccessor<IQuantity, IItem> quantityAccessor(IAttribute<IQuantity> attribute, IType<?> type) {
        try {
            return attribute.getAccessor((IType<IItem>) type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static IAttribute<?> findAttribute(IItemCollection typed, String field) {
        for (IItemIterable iterable : typed) {
            IType<?> type = iterable.getType();
            if (type == null) {
                continue;
            }
            for (IAttribute<?> attribute : type.getAttributes()) {
                if (attribute != null && attribute.getIdentifier().equals(field)) {
                    return attribute;
                }
            }
        }
        return null;
    }

    private static IAttribute<?> findAttributeOnItem(IItem item, String field) {
        IType<?> type = item.getType();
        if (type == null) {
            return null;
        }
        for (IAttribute<?> attribute : type.getAttributes()) {
            if (attribute != null && attribute.getIdentifier().equals(field)) {
                return attribute;
            }
        }
        return null;
    }

    private static Map<String, FlagValue> mergeFlags(IItemCollection events) {
        Map<String, FlagValue> merged = new LinkedHashMap<>();
        Map<String, IType<?>> types = JfrEventSummarizer.discoverTypes(events);
        for (String typeId : types.keySet()) {
            if (!isFlagType(typeId)) {
                continue;
            }
            String kind = flagKind(typeId);
            for (IItemIterable iterable : events.apply(ItemFilters.type(typeId))) {
                for (IItem item : iterable) {
                    String name = stringField(item, "name");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    long observedAt = epochMillis(item);
                    FlagValue existing = merged.get(name);
                    if (existing != null && existing.observedAt() >= observedAt) {
                        continue;
                    }
                    merged.put(
                            name,
                            new FlagValue(
                                    name,
                                    kind,
                                    readValue(item, findAttributeOnItem(item, "value")),
                                    stringField(item, "origin"),
                                    observedAt));
                }
            }
        }
        return merged;
    }

    private static boolean isFlagType(String typeId) {
        String simple = typeId.contains(".") ? typeId.substring(typeId.lastIndexOf('.') + 1) : typeId;
        return FLAG_SUFFIXES.contains(simple);
    }

    private static String flagKind(String typeId) {
        String simple = typeId.contains(".") ? typeId.substring(typeId.lastIndexOf('.') + 1) : typeId;
        return simple;
    }

    private static String stringField(IItem item, String field) {
        IAttribute<?> attribute = findAttributeOnItem(item, field);
        if (attribute == null) {
            return null;
        }
        Object value = readValue(item, attribute);
        return value == null ? null : String.valueOf(value);
    }

    private static long epochMillis(IItem item) {
        IQuantity time = readQuantity(item, JfrAttributes.START_TIME);
        if (time == null) {
            return 0L;
        }
        try {
            return time.longValueIn(UnitLookup.EPOCH_MS);
        } catch (QuantityConversionException ignored) {
            return 0L;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventTypesDocument(AnalysisEnvelope context, List<JfrEventSummarizer.EventTypeRow> types) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventsDocument(
            AnalysisEnvelope context,
            String view,
            String field,
            Map<String, JfrEventSummarizer.EventSummary> summary,
            Map<String, List<Map<String, Object>>> records,
            Map<String, List<SeriesPoint>> series) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeriesPoint(long startTimeNs, Number value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JvmDocument(AnalysisEnvelope context, Map<String, Object> jvm) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlagValue(String name, String kind, Object value, String origin, long observedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JvmFlagsDocument(AnalysisEnvelope context, Map<String, FlagValue> flags) {}
}
