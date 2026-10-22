package io.jafra.analyzer.v2.frames;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.catalogue.EventSpec;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.EventRow;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;

@ApplicationScoped
public class EventService {
    static final int LIMIT = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final Catalogue catalogue;

    @Inject
    public EventService(V2Store store, Catalogue catalogue) {
        this.store = store;
        this.catalogue = catalogue;
    }

    public EventList list(
            JvmRow jvm, String type, String sort, Integer limit, String where, String last, String from, String to) {
        if (type == null || catalogue.event(type) == null) {
            throw V2Exception.unknownMetric(
                    "unknown event type", catalogue.events().stream().map(EventSpec::type).toList());
        }
        EventSpec spec = catalogue.event(type);
        if (!spec.onJdk(jvm.jdkMajor())) {
            throw V2Exception.notOnJdk("needs JDK " + spec.jdk());
        }
        String order = sort == null || sort.isBlank() ? "time" : sort.trim();
        if (!"time".equals(order) && !"duration".equals(order)) {
            throw V2Exception.unsupportedStat("sort is time or duration", List.of("time", "duration"));
        }
        int cap = limit == null ? LIMIT : Math.min(LIMIT, Math.max(1, limit));
        String field = null;
        String expected = null;
        if (where != null && !where.isBlank()) {
            int eq = where.indexOf('=');
            if (eq <= 0 || eq == where.length() - 1) {
                throw V2Exception.badRange("where is one field=value");
            }
            field = where.substring(0, eq).trim();
            expected = where.substring(eq + 1).trim();
            if (!field.matches("[A-Za-z0-9_.]+")) {
                throw V2Exception.badRange("where field is not a name");
            }
        }
        V2Range range = range(jvm, last, from, to);
        try {
            List<EventRow> rows = store.queryEvents(
                    jvm.jvmId(), type, range.from().toEpochMilli(), range.to().toEpochMilli(), order, field, expected, cap);
            MapPauses pauses = "gc.evac_failed".equals(type) ? pauses(jvm, range) : null;
            List<EventHit> hits = new ArrayList<>();
            for (EventRow row : rows) {
                ObjectNode fields = fields(row.fields());
                if (pauses != null && !fields.hasNonNull("pause_us")) {
                    Long pause = pauses.forGc(fields.path("gc_id").asLong(-1));
                    if (pause != null) {
                        fields.put("pause_us", pause);
                    }
                }
                hits.add(new EventHit(
                        row.type(),
                        V2Times.text(row.ts()),
                        row.durationUs(),
                        row.thread(),
                        row.topFrame(),
                        fields));
            }
            return new EventList(new TimeWindow(V2Times.text(range.from()), V2Times.text(range.to())), hits.size(), hits);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private MapPauses pauses(JvmRow jvm, V2Range range) throws IOException {
        List<EventRow> pauses = store.queryEvents(
                jvm.jvmId(), "gc.pause", range.from().toEpochMilli(), range.to().toEpochMilli(), "time", null, null, 200);
        return new MapPauses(pauses);
    }

    private static ObjectNode fields(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (IOException ignored) {
            // return an empty object when the stored JSON cannot be read
        }
        return JSON.createObjectNode();
    }

    private static V2Range range(JvmRow jvm, String last, String from, String to) {
        Instant end = Instant.ofEpochMilli(Math.max(jvm.lastMs(), jvm.firstMs()));
        return V2Range.parse(last, from, to, end);
    }

    private static final class MapPauses {
        private final List<EventRow> pauses;

        MapPauses(List<EventRow> pauses) {
            this.pauses = pauses;
        }

        Long forGc(long gcId) {
            if (gcId < 0) {
                return null;
            }
            for (EventRow pause : pauses) {
                try {
                    JsonNode node = JSON.readTree(pause.fields() == null ? "{}" : pause.fields());
                    if (node.path("gc_id").asLong(-1) == gcId) {
                        if (pause.durationUs() != null) {
                            return pause.durationUs();
                        }
                        if (node.has("pause_us")) {
                            return node.path("pause_us").asLong();
                        }
                    }
                } catch (IOException ignored) {
                    // skip a pause row whose fields are not JSON
                }
            }
            return null;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimeWindow(String from, String to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventHit(
            String type, String ts, Long durationUs, String thread, String topFrame, ObjectNode fields) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventList(TimeWindow window, int n, List<EventHit> events) {}
}
