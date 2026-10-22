package io.jafra.analyzer.v2.incidents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Action;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Mark;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Open;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.rollup.WindowCommitted;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.surface.SurfaceService;
import io.jafra.analyzer.v2.surface.SurfaceService.Lane;

@ApplicationScoped
public class IncidentEngine {
    private static final Logger LOG = Logger.getLogger(IncidentEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final List<Detector> detectors;
    private final Event<WindowAssessed> assessed;
    private final BaselineService baselines;
    private final SurfaceService surface;
    private final List<TogetherRule> rules;
    private final ConcurrentHashMap<String, Track> tracks = new ConcurrentHashMap<>();

    @Inject
    public IncidentEngine(
            V2Store store,
            Instance<Detector> detectors,
            Instance<TogetherRule> rules,
            Event<WindowAssessed> assessed,
            BaselineService baselines,
            SurfaceService surface) {
        this(store, copy(detectors), copyRules(rules), assessed, baselines, surface);
    }

    IncidentEngine(
            V2Store store,
            List<Detector> detectors,
            Event<WindowAssessed> assessed,
            BaselineService baselines,
            SurfaceService surface) {
        this(store, detectors, List.of(), assessed, baselines, surface);
    }

    IncidentEngine(
            V2Store store,
            List<Detector> detectors,
            List<TogetherRule> rules,
            Event<WindowAssessed> assessed,
            BaselineService baselines,
            SurfaceService surface) {
        this.store = store;
        this.detectors = List.copyOf(detectors);
        this.rules = List.copyOf(rules);
        this.assessed = assessed;
        this.baselines = baselines;
        this.surface = surface;
        try {
            rehydrate();
        } catch (IOException error) {
            throw new IllegalStateException("unable to rehydrate open incidents", error);
        }
    }

    void onWindow(@Observes WindowCommitted committed) {
        if (committed.late()) {
            LOG.infof(
                    "late window %s jvm %d ts %s: rolled up, detectors not re-run",
                    committed.chunkId(),
                    committed.jvmId(),
                    V2Times.text(committed.fromMs()));
            return;
        }
        try {
            JvmRow jvm = store.jvm(committed.jvmId());
            if (jvm == null) {
                return;
            }
            WindowInput input = new WindowInput(
                    jvm,
                    committed.fromMs(),
                    committed.toMs(),
                    WindowViews.rollup(store, jvm.jvmId(), committed.fromMs()),
                    WindowViews.samples(store, jvm.jvmId(), committed.fromMs(), committed.toMs()),
                    WindowViews.events(store, jvm.jvmId(), committed.fromMs(), committed.toMs()),
                    baselines.bind(jvm.jvmId(), committed.fromMs()),
                    new JvmSettings(jvm));
            List<String> opened = new ArrayList<>();
            List<String> closed = new ArrayList<>();
            Map<String, WindowVerdict> verdicts = new LinkedHashMap<>();
            List<Detector> active = new ArrayList<>();
            for (Detector detector : detectors) {
                if (!supports(detector, jvm.jdkMajor())) {
                    continue;
                }
                verdicts.put(detector.type(), detector.evaluate(input));
                active.add(detector);
            }
            List<IncidentRecord> open = new ArrayList<>();
            for (IncidentRecord row : store.openIncidents()) {
                if (row.jvmId() == jvm.jvmId()) {
                    open.add(row);
                }
            }
            TogetherContext context = new TogetherContext(verdicts, open);
            for (TogetherRule rule : rules) {
                rule.apply(context);
            }
            for (Detector detector : active) {
                WindowVerdict verdict = verdicts.get(detector.type());
                String id = apply(jvm, detector, verdict, committed.fromMs(), committed.toMs());
                Track track = tracks.get(key(jvm.jvmId(), detector.type()));
                if (id == null || track == null) {
                    continue;
                }
                if (track.closedId != null) {
                    closed.add(track.closedId);
                    track.closedId = null;
                } else if (track.opened) {
                    opened.add(id);
                    track.opened = false;
                }
            }
            if (assessed != null) {
                assessed.fire(new WindowAssessed(jvm.jvmId(), committed.fromMs(), committed.toMs(), opened, closed));
            }
        } catch (RuntimeException | IOException error) {
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

    private String apply(JvmRow jvm, Detector detector, WindowVerdict verdict, long fromMs, long toMs) throws IOException {
        Track track = tracks.computeIfAbsent(key(jvm.jvmId(), detector.type()), ignored -> new Track());
        double scored = score(verdict);
        Mark mark = new Mark(fromMs, toMs, verdict, scored);
        Action action = track.life.accept(detector.kind(), mark);
        if (action == Action.NONE) {
            return track.id;
        }
        Open evidence = action == Action.CLOSE ? track.life.lastClosed() : track.life.open();
        if (evidence == null || evidence.worst() == null) {
            return track.id;
        }
        if (action == Action.OPEN) {
            track.id = allocate(detector.type(), evidence.t0());
            track.opened = true;
        }
        for (long ts : track.life.recordedThisStep()) {
            store.addIncidentWindow(track.id, jvm.jvmId(), detector.group(), ts);
        }
        boolean related = related(jvm.jvmId(), detector.type(), fromMs, toMs);
        String status = action == Action.CLOSE ? "closed" : "open";
        Long t1 = action == Action.CLOSE ? track.life.lastBadTo() : null;
        WindowVerdict worst = evidence.worst().verdict();
        WindowVerdict latest = evidence.latest().verdict();
        String level = level(evidence.worst().state());
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
                text(worst.headline()),
                text(worst.felt()),
                when(evidence, t1, status),
                howBad(evidence, level, confidence),
                together(jvm, detector.type(), evidence.worst()),
                who(worst.who()),
                where(worst.where()),
                JSON.writeValueAsString(worst.todo()),
                sources(worst, latest),
                flags(worst, latest));
        store.upsertIncident(row);
        if (action == Action.CLOSE) {
            track.closedId = track.id;
            track.id = null;
        }
        return action == Action.CLOSE ? track.closedId : track.id;
    }

    private void rehydrate() throws IOException {
        for (IncidentRecord row : store.openIncidents()) {
            long lastBadTs = store.lastIncidentWindowTs(row.id());
            if (lastBadTs < 0) {
                lastBadTs = row.t0();
            }
            Long end = store.windowEnd(row.jvmId(), lastBadTs);
            long badTo = end == null ? lastBadTs : end;
            int after = store.countWindowsAfter(row.jvmId(), lastBadTs);
            if (after >= 3) {
                store.upsertIncident(closed(row, badTo));
                continue;
            }
            Track track = new Track();
            track.id = row.id();
            Mark worst = markFrom(row, row.howBadJson(), "worst", row.t0(), badTo);
            Mark latest = markFrom(row, row.howBadJson(), "latest", row.t0(), badTo);
            track.life.restore(row.t0(), worst, latest, after, badTo);
            tracks.put(key(row.jvmId(), row.type()), track);
        }
    }

    private static IncidentRecord closed(IncidentRecord row, long t1) {
        ObjectNode when = JSON.createObjectNode();
        when.put("t0", V2Times.text(row.t0()));
        when.put("t1", V2Times.text(t1));
        when.put("peak_at", V2Times.text(row.peakAt()));
        when.put("status", "closed");
        return new IncidentRecord(
                row.id(),
                row.jvmId(),
                row.type(),
                row.group(),
                row.level(),
                "closed",
                row.t0(),
                t1,
                row.peakAt(),
                row.score(),
                row.confidence(),
                row.headline(),
                row.felt(),
                when.toString(),
                row.howBadJson(),
                row.togetherJson(),
                row.whoJson(),
                row.whereJson(),
                row.todoJson(),
                row.sourcesJson(),
                row.flagsJson());
    }

    private Mark markFrom(IncidentRecord row, String howBad, String which, long fromMs, long toMs) {
        JsonNode evidence = JSON.createObjectNode();
        try {
            if (howBad != null && !howBad.isBlank()) {
                evidence = JSON.readTree(howBad).path("evidence").path(which);
            }
        } catch (IOException ignored) {
            evidence = JSON.createObjectNode();
        }
        State state = "critical".equals(row.level()) ? State.CRITICAL : "warning".equals(row.level()) ? State.WARNING : State.OK;
        Double value = evidence.hasNonNull("value") ? evidence.get("value").asDouble() : null;
        Band band = evidence.has("band")
                ? new Band(
                        evidence.get("band").hasNonNull("warning") ? evidence.get("band").get("warning").asDouble() : null,
                        evidence.get("band").hasNonNull("critical") ? evidence.get("band").get("critical").asDouble() : null)
                : null;
        long at = fromMs;
        if (evidence.hasNonNull("at")) {
            try {
                at = V2Times.parse(evidence.get("at").asText(), "at").toEpochMilli();
            } catch (RuntimeException ignored) {
                at = fromMs;
            }
        }
        WindowVerdict verdict = new WindowVerdict(
                state,
                value,
                evidence.path("unit").asText(null),
                evidence.hasNonNull("limit") ? evidence.get("limit").asDouble() : null,
                band,
                evidence.path("family").asText(null),
                evidence.path("reason").asText(""),
                parseWho(row.whoJson()),
                parseWhere(row.whereJson()),
                parseTodo(row.todoJson()),
                row.felt(),
                row.headline(),
                parseEvents(row.sourcesJson()),
                parseFlags(row.flagsJson()),
                evidence.hasNonNull("measured_at") ? V2Times.parse(evidence.get("measured_at").asText(), "measured_at").toEpochMilli() : null);
        return new Mark(at, toMs, verdict, row.score());
    }

    private String allocate(String type, long t0) throws IOException {
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

    private boolean related(int jvmId, String type, long fromMs, long toMs) throws IOException {
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

    private String together(JvmRow jvm, String type, Mark worst) throws IOException {
        ArrayNode related = JSON.createArrayNode();
        long fromMs = worst.fromMs();
        long toMs = worst.toMs();
        for (IncidentRecord row : store.incidents(jvm.jvmId())) {
            if (type.equals(row.type())) {
                continue;
            }
            if (row.t0() < toMs && (row.t1() == null || row.t1() >= fromMs)) {
                related.add(JSON.createObjectNode().put("id", row.id()).put("type", row.type()));
            }
        }
        ArrayNode groups = JSON.createArrayNode();
        List<Lane> lanes = new ArrayList<>(surface.overviewLanes(jvm, fromMs, toMs));
        lanes.removeIf(lane -> lane.vsNormal() == null);
        lanes.sort(Comparator.comparingDouble((Lane lane) -> Math.abs(lane.vsNormal())).reversed());
        int kept = 0;
        for (Lane lane : lanes) {
            if (kept == 5) {
                break;
            }
            groups.add(JSON.createObjectNode()
                    .put("group", lane.group())
                    .put("metric", lane.metric())
                    .put("vs_normal", lane.vsNormal()));
            kept++;
        }
        ObjectNode together = JSON.createObjectNode();
        together.set("related", related);
        together.set("groups", groups);
        return together.toString();
    }

    private static double score(WindowVerdict verdict) {
        if (verdict.state() == State.UNKNOWN || verdict.value() == null || verdict.band() == null) {
            return 0d;
        }
        return IncidentLifecycle.score(verdict.value(), verdict.band());
    }

    private static String level(State state) {
        if (state == State.CRITICAL) {
            return "critical";
        }
        if (state == State.WARNING) {
            return "warning";
        }
        return "ok";
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

    private static String howBad(Open evidence, String level, String confidence) {
        ObjectNode node = JSON.createObjectNode();
        node.put("level", level);
        node.put("score", evidence.worst().score());
        node.put("confidence", confidence);
        ObjectNode points = JSON.createObjectNode();
        points.set("worst", point(evidence.worst()));
        points.set("latest", point(evidence.latest()));
        node.set("evidence", points);
        return node.toString();
    }

    private static ObjectNode point(Mark mark) {
        WindowVerdict verdict = mark.verdict();
        ObjectNode node = JSON.createObjectNode();
        if (verdict.value() == null) {
            node.putNull("value");
        } else {
            node.put("value", verdict.value());
        }
        node.put("unit", verdict.unit());
        if (verdict.limit() == null) {
            node.putNull("limit");
        } else {
            node.put("limit", verdict.limit());
        }
        ObjectNode band = JSON.createObjectNode();
        if (verdict.band() == null || verdict.band().warning() == null) {
            band.putNull("warning");
        } else {
            band.put("warning", verdict.band().warning());
        }
        if (verdict.band() == null || verdict.band().critical() == null) {
            band.putNull("critical");
        } else {
            band.put("critical", verdict.band().critical());
        }
        node.set("band", band);
        node.put("family", verdict.family());
        node.put("reason", verdict.reason());
        node.put("at", V2Times.text(mark.fromMs()));
        if (verdict.measuredAt() != null) {
            node.put("measured_at", V2Times.text(verdict.measuredAt()));
        }
        node.set("flags", strings(verdict.flags()));
        return node;
    }

    private static String who(Who who) throws IOException {
        if (who == null) {
            return "null";
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", who.kind());
        node.put("name", who.name());
        if (who.sharePct() == null) {
            node.putNull("share_pct");
        } else {
            node.put("share_pct", who.sharePct());
        }
        node.put("estimate", who.estimate());
        return node.toString();
    }

    private static String where(List<WhereFrame> frames) {
        ArrayNode array = JSON.createArrayNode();
        if (frames != null) {
            for (WhereFrame frame : frames) {
                ObjectNode node = JSON.createObjectNode();
                node.put("frame", frame.frame());
                node.put("share_pct", frame.sharePct());
                node.set("source_events", strings(frame.sourceEvents()));
                array.add(node);
            }
        }
        return array.toString();
    }

    private static String sources(WindowVerdict worst, WindowVerdict latest) {
        ObjectNode node = JSON.createObjectNode();
        node.set("events", strings(unionEvents(worst, latest)));
        node.set("flags", strings(unionFlags(worst, latest)));
        return node.toString();
    }

    private static String flags(WindowVerdict worst, WindowVerdict latest) {
        return strings(unionFlags(worst, latest)).toString();
    }

    private static Set<String> unionEvents(WindowVerdict worst, WindowVerdict latest) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (worst.sourceEvents() != null) {
            names.addAll(worst.sourceEvents());
        }
        if (latest != worst && latest.sourceEvents() != null) {
            names.addAll(latest.sourceEvents());
        }
        return names;
    }

    private static Set<String> unionFlags(WindowVerdict worst, WindowVerdict latest) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (worst.flags() != null) {
            names.addAll(worst.flags());
        }
        if (latest != worst && latest.flags() != null) {
            names.addAll(latest.flags());
        }
        return names;
    }

    private static ArrayNode strings(Iterable<String> values) {
        ArrayNode array = JSON.createArrayNode();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private static Who parseWho(String json) {
        try {
            if (json == null || json.isBlank() || "null".equals(json)) {
                return null;
            }
            JsonNode node = JSON.readTree(json);
            if (node.isNull()) {
                return null;
            }
            return new Who(
                    node.path("kind").asText(""),
                    node.path("name").asText(""),
                    node.hasNonNull("share_pct") ? node.get("share_pct").asDouble() : null,
                    node.path("estimate").asBoolean(false));
        } catch (IOException error) {
            return null;
        }
    }

    private static List<WhereFrame> parseWhere(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            List<WhereFrame> frames = new ArrayList<>();
            for (JsonNode node : JSON.readTree(json)) {
                List<String> sources = new ArrayList<>();
                for (JsonNode source : node.path("source_events")) {
                    sources.add(source.asText());
                }
                frames.add(new WhereFrame(node.path("frame").asText(""), node.path("share_pct").asDouble(), sources));
            }
            return frames;
        } catch (IOException error) {
            return List.of();
        }
    }

    private static List<String> parseTodo(String json) {
        return texts(json);
    }

    private static List<String> parseEvents(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return texts(JSON.readTree(json).path("events").toString());
        } catch (IOException error) {
            return List.of();
        }
    }

    private static Set<String> parseFlags(String json) {
        return Set.copyOf(texts(json));
    }

    private static List<String> texts(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            JsonNode node = JSON.readTree(json);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    values.add(item.asText());
                }
            }
            return values;
        } catch (IOException error) {
            return List.of();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<TogetherRule> copyRules(Instance<TogetherRule> rules) {
        List<TogetherRule> found = new ArrayList<>();
        if (rules != null) {
            rules.forEach(found::add);
        }
        return found;
    }

    private static List<Detector> copy(Instance<Detector> detectors) {
        List<Detector> found = new ArrayList<>();
        detectors.forEach(found::add);
        return found;
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
