package io.jafra.analyzer.v2.incidents;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Exception;
import io.jafra.analyzer.v2.api.V2Range;
import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;

@ApplicationScoped
public class IncidentService {
    private static final int CAP = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;

    @Inject
    public IncidentService(V2Store store) {
        this.store = store;
    }

    public ObjectNode list(JvmRow jvm, String level, String status, String group, String last, String from, String to) {
        try {
            Instant end = Instant.ofEpochMilli(Math.max(jvm.lastMs(), jvm.firstMs()));
            V2Range range = V2Range.parse(last, from, to, end);
            long fromMs = range.from().toEpochMilli();
            long toMs = range.to().toEpochMilli();
            boolean statusFilter = status != null && !status.isBlank() && !"all".equals(status);
            List<IncidentRecord> rows = new ArrayList<>();
            for (IncidentRecord row : store.incidents(jvm.jvmId())) {
                if (row.t0() > toMs || (row.t1() != null && row.t1() < fromMs)) {
                    continue;
                }
                if (level != null && !level.isBlank() && !level.equals(row.level())) {
                    continue;
                }
                if (statusFilter && !status.equals(row.status())) {
                    continue;
                }
                if (group != null && !group.isBlank() && !group.equals(row.group())) {
                    continue;
                }
                rows.add(row);
            }
            rows.sort(Comparator
                    .comparingInt((IncidentRecord row) -> "critical".equals(row.level()) ? 0 : "warning".equals(row.level()) ? 1 : 2)
                    .thenComparingInt(row -> row.t1() == null ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(IncidentRecord::t0).reversed()));
            int total = rows.size();
            boolean truncated = total > CAP;
            if (truncated) {
                rows = new ArrayList<>(rows.subList(0, CAP));
            }
            ArrayNode incidents = JSON.createArrayNode();
            for (IncidentRecord row : rows) {
                incidents.add(summary(row));
            }
            ObjectNode body = JSON.createObjectNode();
            ObjectNode window = body.putObject("window");
            window.put("from", V2Times.text(range.from()));
            window.put("to", V2Times.text(range.to()));
            body.set("incidents", incidents);
            if (truncated) {
                body.put("total", total);
                body.put("truncated", true);
            }
            return body;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public List<String> latestIds(JvmRow jvm) {
        try {
            List<IncidentRecord> rows = store.incidents(jvm.jvmId());
            rows.sort(Comparator.comparingLong(IncidentRecord::t0).reversed());
            List<String> latest = new ArrayList<>();
            for (IncidentRecord candidate : rows) {
                if (latest.size() == 5) {
                    break;
                }
                latest.add(candidate.id());
            }
            return latest;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public ObjectNode get(JvmRow jvm, String id) {
        try {
            IncidentRecord row = store.incident(id);
            if (row == null || row.jvmId() != jvm.jvmId()) {
                throw V2Exception.unknownIncident(latestIds(jvm));
            }
            return document(row);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ObjectNode summary(IncidentRecord row) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", row.id());
        node.put("type", row.type());
        node.put("group", row.group());
        node.put("level", row.level());
        node.put("status", row.status());
        node.put("t0", V2Times.text(row.t0()));
        if (row.t1() == null) {
            node.putNull("t1");
        } else {
            node.put("t1", V2Times.text(row.t1()));
        }
        node.put("peak_at", V2Times.text(row.peakAt()));
        node.put("headline", row.headline());
        node.put("confidence", row.confidence());
        JsonNode together = tree(row.togetherJson());
        JsonNode related = together.get("related");
        node.set("related", related != null && related.isArray() ? related : JSON.createArrayNode());
        return node;
    }

    private static ObjectNode document(IncidentRecord row) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", row.id());
        node.put("type", row.type());
        node.put("group", row.group());
        node.put("level", row.level());
        node.put("status", row.status());
        node.put("score", row.score());
        node.put("confidence", row.confidence());
        node.put("headline", row.headline());
        node.put("felt", row.felt());
        node.put("t0", V2Times.text(row.t0()));
        if (row.t1() == null) {
            node.putNull("t1");
        } else {
            node.put("t1", V2Times.text(row.t1()));
        }
        node.put("peak_at", V2Times.text(row.peakAt()));
        node.set("when", tree(row.whenJson()));
        node.set("how_bad", tree(row.howBadJson()));
        node.set("together", tree(row.togetherJson()));
        node.set("who", tree(row.whoJson()));
        node.set("where", tree(row.whereJson()));
        node.set("todo", tree(row.todoJson()));
        node.set("sources", tree(row.sourcesJson()));
        node.set("flags", tree(row.flagsJson()));
        return node;
    }

    private static JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (IOException error) {
            return JSON.createObjectNode();
        }
    }
}
