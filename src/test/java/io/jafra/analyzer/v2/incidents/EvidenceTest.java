package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.Kind;
import io.jafra.analyzer.v2.incidents.IncidentLifecycle.State;
import io.jafra.analyzer.v2.rollup.WindowCommitted;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.CommitResult;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.surface.SurfaceService;

class EvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void weakerLaterWindowUpdatesLatestAndKeepsWorstEvidence() throws Exception {
        Path root = Files.createTempDirectory("jafra-evidence");
        V2Store store = new V2Store(root);
        try {
            Queue<WindowVerdict> script = new ArrayDeque<>();
            script.add(verdict(
                    State.CRITICAL, 100d, 7d, new Band(10d, 50d), "B", "worst-reason", "worst headline"));
            script.add(verdict(
                    State.WARNING, 20d, 1d, new Band(4d, 80d), "D", "latest-reason", "latest headline"));
            Catalogue catalogue = Catalogue.load();
            IncidentEngine engine = new IncidentEngine(
                    store,
                    List.of(new ScriptedDetector(script)),
                    null,
                    new BaselineService(store, catalogue),
                    new SurfaceService(store, catalogue, new BaselineService(store, catalogue)));
            feed(store, engine, 0);
            feed(store, engine, 5_000);
            IncidentRecord row = store.incidents(store.findJvms("ns", "pod", "app", "c").get(0).jvmId()).get(0);
            JsonNode howBad = JSON.readTree(row.howBadJson());
            JsonNode worst = howBad.path("evidence").path("worst");
            JsonNode latest = howBad.path("evidence").path("latest");
            assertEquals("worst-reason", worst.path("reason").asText());
            assertEquals(10d, worst.path("band").path("warning").asDouble(), 1e-9);
            assertEquals(50d, worst.path("band").path("critical").asDouble(), 1e-9);
            assertEquals(7d, worst.path("limit").asDouble(), 1e-9);
            assertEquals("B", worst.path("family").asText());
            assertEquals("latest-reason", latest.path("reason").asText());
            assertEquals(4d, latest.path("band").path("warning").asDouble(), 1e-9);
            assertEquals(80d, latest.path("band").path("critical").asDouble(), 1e-9);
            assertEquals(1d, latest.path("limit").asDouble(), 1e-9);
            assertEquals("D", latest.path("family").asText());
            assertNotEquals(worst.path("reason").asText(), latest.path("reason").asText());
            assertEquals("worst headline", row.headline());
        } finally {
            store.close();
        }
    }

    private static void feed(V2Store store, IncidentEngine engine, long fromMs) throws Exception {
        WindowData data = new WindowData();
        data.clusterId = "c";
        data.namespace = "ns";
        data.podName = "pod";
        data.podUid = "uid";
        data.containerName = "app";
        data.jvmStartMs = 1;
        data.jdkMajor = 21;
        data.jdkVersion = "21";
        data.profile = "low";
        data.settingsJson = "{}";
        data.fromMs = fromMs;
        data.toMs = fromMs + 5_000;
        CommitResult result = store.commit("e-" + fromMs, data);
        engine.onWindow(new WindowCommitted("e-" + fromMs, result.jvmId(), fromMs, fromMs + 5_000, false));
    }

    private static WindowVerdict verdict(
            State state, double value, double limit, Band band, String family, String reason, String headline) {
        return new WindowVerdict(
                state,
                value,
                "ms",
                limit,
                band,
                family,
                reason,
                new Who("site", headline, null, false),
                List.of(),
                List.of("look here"),
                "felt",
                headline,
                List.of("jdk.GarbageCollection"),
                Set.of(),
                null);
    }

    private static final class ScriptedDetector implements Detector {
        private final Queue<WindowVerdict> script;

        ScriptedDetector(Queue<WindowVerdict> script) {
            this.script = script;
        }

        @Override
        public String type() {
            return "long_pause";
        }

        @Override
        public String group() {
            return "gc";
        }

        @Override
        public Kind kind() {
            return Kind.INSTANT;
        }

        @Override
        public String family() {
            return "A";
        }

        @Override
        public Set<Integer> jdks() {
            return Set.of(17, 21, 25);
        }

        @Override
        public WindowVerdict evaluate(WindowInput input) {
            return script.remove();
        }
    }
}
