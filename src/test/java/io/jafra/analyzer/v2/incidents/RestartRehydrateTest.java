package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.rollup.WindowCommitted;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.CommitResult;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.surface.SurfaceService;

class RestartRehydrateTest {
    @Test
    void threeGoodWindowsCloseTheRehydratedIncidentWithTheSameId() throws Exception {
        Path root = Files.createTempDirectory("jafra-restart");
        V2Store store = new V2Store(root);
        try {
            CommitResult first = commit(store, 0);
            String id = "long_pause-0";
            store.upsertIncident(open(id, first.jvmId()));
            store.addIncidentWindow(id, first.jvmId(), "gc", 0);
            IncidentEngine engine = engine(store);
            assertEquals("open", store.incident(id).status());
            for (int i = 1; i <= 3; i++) {
                long from = i * 5_000L;
                CommitResult result = commit(store, from);
                engine.onWindow(new WindowCommitted("w-" + from, result.jvmId(), from, from + 5_000, false));
            }
            IncidentRecord closed = store.incident(id);
            assertEquals(id, closed.id());
            assertEquals("closed", closed.status());
            assertEquals(5_000L, closed.t1());
        } finally {
            store.close();
        }
    }

    @Test
    void openIncidentAlreadyFollowedByThreeWindowsClosesAtStartup() throws Exception {
        Path root = Files.createTempDirectory("jafra-restart-startup");
        V2Store store = new V2Store(root);
        try {
            int jvmId = 0;
            for (int i = 0; i < 4; i++) {
                jvmId = commit(store, i * 5_000L).jvmId();
            }
            String id = "long_pause-0";
            store.upsertIncident(open(id, jvmId));
            store.addIncidentWindow(id, jvmId, "gc", 0);
            engine(store);
            IncidentRecord closed = store.incident(id);
            assertNotNull(closed);
            assertEquals(id, closed.id());
            assertEquals("closed", closed.status());
            assertEquals(5_000L, closed.t1());
        } finally {
            store.close();
        }
    }

    private static IncidentEngine engine(V2Store store) {
        Catalogue catalogue = Catalogue.load();
        BaselineService baselines = new BaselineService(store, catalogue);
        return new IncidentEngine(
                store,
                List.of(new LongPauseDetector(200, 1000, 85, new HeapSizeAdvice(90))),
                null,
                baselines,
                new SurfaceService(store, catalogue, baselines, null, null));
    }

    private static CommitResult commit(V2Store store, long fromMs) throws Exception {
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
        return store.commit("r-" + fromMs, data);
    }

    private static IncidentRecord open(String id, int jvmId) {
        String evidence = """
                {"value":250,"unit":"ms","limit":null,"band":{"warning":200,"critical":1000},"family":"A","reason":"seed","at":"1970-01-01T00:00:00.000Z","flags":[]}
                """.trim();
        String howBad = "{\"level\":\"warning\",\"score\":60,\"confidence\":\"medium\",\"evidence\":{\"worst\":"
                + evidence + ",\"latest\":" + evidence + "}}";
        return new IncidentRecord(
                id,
                jvmId,
                "long_pause",
                "gc",
                "warning",
                "open",
                0L,
                null,
                0L,
                60d,
                "medium",
                "seed headline",
                "felt",
                "{\"t0\":\"1970-01-01T00:00:00.000Z\",\"t1\":null,\"peak_at\":\"1970-01-01T00:00:00.000Z\",\"status\":\"open\"}",
                howBad,
                "{\"related\":[],\"groups\":[]}",
                "null",
                "[]",
                "[]",
                "{\"events\":[],\"flags\":[]}",
                "[]");
    }
}
