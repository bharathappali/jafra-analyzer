package io.jafra.analyzer.v2.incidents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Action;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Kind;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Mark;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Open;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.rollup.WindowCommitted;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.store.V2Store.RollupRow;

@ApplicationScoped
public class IncidentEngine {
    private static final Logger LOG = Logger.getLogger(IncidentEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final Instance<Detector> detectors;
    private final Event<WindowAssessed> assessed;
    private final ConcurrentHashMap<String, Track> tracks = new ConcurrentHashMap<>();

    @Inject
    public IncidentEngine(V2Store store, Instance<Detector> detectors, Event<WindowAssessed> assessed) {
        this.store = store;
        this.detectors = detectors;
        this.assessed = assessed;
    }

    void onWindow(@Observes WindowCommitted committed) {
        if (committed.late()) {
            return;
        }
        try {
            JvmRow jvm = store.jvm(committed.jvmId());
            if (jvm == null) {
                return;
            }
            Map<String, RollupRow> totals = store.readTotalsAt(committed.jvmId(), committed.fromMs());
            List<RollupRow> baseline = store.recentTotals(committed.jvmId(), "gc.pause.us", "gc", committed.toMs(), 120);
            long gcCount = 0;
            for (RollupRow row : baseline) {
                gcCount += row.count();
            }
            WindowInput input = new WindowInput(
                    jvm,
                    committed.fromMs(),
                    committed.toMs(),
                    totals,
                    store.readEvents(committed.jvmId(), null, committed.fromMs(), committed.toMs(), false, 200),
                    store.readSamples(committed.jvmId(), null, committed.fromMs(), committed.toMs(), null, null, null),
                    baseline.size() >= 60,
                    gcCount,
                    null);
            List<String> opened = new ArrayList<>();
            List<String> closed = new ArrayList<>();
            for (Detector detector : detectors) {
                if (!supports(detector, jvm.jdkMajor())) {
                    continue;
                }
                Verdict verdict = detector.evaluate(input);
                String id = apply(jvm, detector, verdict, committed.fromMs(), committed.toMs());
                if (id == null) {
                    continue;
                }
                Track track = tracks.get(key(jvm.jvmId(), detector.type()));
                if (track != null && track.closedId != null) {
                    closed.add(track.closedId);
                    track.closedId = null;
                } else if (track != null && track.opened) {
                    opened.add(id);
                    track.opened = false;
                }
            }
            if (assessed != null) {
                assessed.fire(new WindowAssessed(jvm.jvmId(), committed.fromMs(), committed.toMs(), opened, closed));
            }
        } catch (RuntimeException | java.io.IOException error) {
            LOG.warnf(error, "incident engine skipped %s", committed.chunkId());
        }
    }

    /** 17, 21, and 25 are the catalogue JDKs. A runtime between them uses the lower set. */
    private static boolean supports(Detector detector, int major) {
        if (detector.jdks().contains(major)) {
            return true;
        }
        if (major > 21) {
            return detector.jdks().contains(21);
        }
        return false;
    }

    private String apply(JvmRow jvm, Detector detector, Verdict verdict, long fromMs, long toMs) throws java.io.IOException {
        if (verdict.state() == State.UNKNOWN && tracks.get(key(jvm.jvmId(), detector.type())) == null) {
            Track created = tracks.computeIfAbsent(key(jvm.jvmId(), detector.type()), ignored -> new Track());
            created.life.accept(detector.kind(), mark(verdict, fromMs, toMs));
            return null;
        }
        Track track = tracks.computeIfAbsent(key(jvm.jvmId(), detector.type()), ignored -> new Track());
        double scored = IncidentLifecycle.score(verdict.value(), verdict.warning(), verdict.critical());
        Mark mark = new Mark(fromMs, toMs, verdict.state(), verdict.value(), scored, verdict.headline());
        Action action = track.life.accept(detector.kind(), mark);
        if (action == Action.NONE) {
            return track.id;
        }
        boolean related = related(jvm.jvmId(), detector.type(), fromMs, toMs);
        Open evidence = action == Action.CLOSE ? track.life.lastClosed() : track.life.open();
        if (evidence == null) {
            return track.id;
        }
        if (action == Action.OPEN) {
            track.id = allocate(detector.type(), evidence.t0());
            track.opened = true;
        }
        String status = action == Action.CLOSE ? "closed" : "open";
        Long t1 = action == Action.CLOSE ? track.life.lastBadTo() : null;
        String level = worse(evidence.worst().state());
        String confidence = IncidentLifecycle.confidence(evidence.worst().score(), related);
        IncidentRecord row = new IncidentRecord(
                track.id,
                jvm.jvmId(),
                detector.type(),
                detector.group(),
                level,
                status,
                evidence.t0(),
                t1,
                evidence.peakAt(),
                evidence.worst().score(),
                confidence,
                evidence.worst().headline(),
                verdict.felt(),
                when(evidence, t1, status),
                howBad(evidence, verdict),
                together(jvm.jvmId(), detector.type(), fromMs, toMs),
                who(verdict),
                "[]",
                JSON.writeValueAsString(verdict.todo() == null ? List.of() : verdict.todo()),
                "{\"events\":[\"jdk.GarbageCollection\"]}",
                JSON.writeValueAsString(verdict.flags() == null ? List.of() : verdict.flags()));
        store.upsertIncident(row);
        if (verdict.state() == State.WARNING || verdict.state() == State.CRITICAL) {
            store.addIncidentWindow(track.id, jvm.jvmId(), detector.group(), fromMs);
        }
        if (action == Action.CLOSE) {
            track.closedId = track.id;
            track.id = null;
        }
        return action == Action.CLOSE ? track.closedId : track.id;
    }

    private String allocate(String type, long t0) throws java.io.IOException {
        String base = type + "-" + (t0 / 1000L);
        if (!store.incidentExists(base)) {
            return base;
        }
        for (int n = 2; n < 100; n++) {
            String id = base + "-" + n;
            if (!store.incidentExists(id)) {
                return id;
            }
        }
        return base + "-" + System.nanoTime();
    }

    private boolean related(int jvmId, String type, long fromMs, long toMs) throws java.io.IOException {
        for (IncidentRecord row : store.incidents(jvmId)) {
            if (type.equals(row.type())) {
                continue;
            }
            if (row.t0() < toMs && (row.t1() == null || row.t1() >= fromMs)) {
                return true;
            }
        }
        return false;
    }

    private String together(int jvmId, String type, long fromMs, long toMs) throws java.io.IOException {
        ArrayNode related = JSON.createArrayNode();
        for (IncidentRecord row : store.incidents(jvmId)) {
            if (type.equals(row.type())) {
                continue;
            }
            if (row.t0() < toMs && (row.t1() == null || row.t1() >= fromMs)) {
                related.add(JSON.createObjectNode().put("id", row.id()).put("type", row.type()));
            }
        }
        ObjectNode together = JSON.createObjectNode();
        together.set("related", related);
        together.set("groups", JSON.createArrayNode());
        return together.toString();
    }

    private static Mark mark(Verdict verdict, long fromMs, long toMs) {
        return new Mark(fromMs, toMs, verdict.state(), verdict.value(), 0, verdict.headline());
    }

    private static String worse(State state) {
        return state == State.CRITICAL ? "critical" : state == State.WARNING ? "warning" : "ok";
    }

    private static String when(Open evidence, Long t1, String status) {
        ObjectNode node = JSON.createObjectNode();
        node.put("t0", V2Times.text(evidence.t0()));
        if (t1 == null) {
            node.putNull("t1");
        } else {
            node.put("t1", V2Times.text(t1));
        }
        node.put("peak_at", V2Times.text(evidence.peakAt()));
        node.put("status", status);
        return node.toString();
    }

    private static String howBad(Open evidence, Verdict verdict) {
        ObjectNode node = JSON.createObjectNode();
        node.put("level", worse(evidence.worst().state()));
        node.put("score", evidence.worst().score());
        ObjectNode evidenceNode = JSON.createObjectNode();
        evidenceNode.set("worst", point(evidence.worst(), verdict));
        evidenceNode.set("latest", point(evidence.latest(), verdict));
        node.set("evidence", evidenceNode);
        return node.toString();
    }

    private static ObjectNode point(Mark mark, Verdict verdict) {
        ObjectNode node = JSON.createObjectNode();
        node.put("value", mark.value());
        node.put("unit", verdict.unit());
        node.put("reason", verdict.reason());
        node.put("at", V2Times.text(mark.fromMs()));
        return node;
    }

    private static String who(Verdict verdict) {
        return JSON.createObjectNode().put("kind", "site").put("name", verdict.who()).toString();
    }

    private static String key(int jvmId, String type) {
        return jvmId + "\0" + type;
    }

    private static final class Track {
        final IncidentLifecycle life = new IncidentLifecycle();
        String id;
        boolean opened;
        String closedId;
    }

    public record WindowAssessed(int jvmId, long fromMs, long toMs, List<String> opened, List<String> closed) {}
}
