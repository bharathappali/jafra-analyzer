package io.jafra.analyzer.v2.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.jafra.analyzer.v2.catalogue.Catalogue;
import io.jafra.analyzer.v2.rollup.WindowData;
import io.jafra.analyzer.v2.store.V2Store;

class BaselineServiceTest {
    @Test
    void currentWindowDoesNotMoveTheMedian() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            fixture.commit(0, "cpu.used.cores", 1);
            fixture.commit(5_000, "cpu.used.cores", 2);
            fixture.commit(10_000, "cpu.used.cores", 3);
            fixture.commit(15_000, "cpu.used.cores", 4);
            fixture.commit(20_000, "cpu.used.cores", 100);
            Baseline baseline = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "cpu", "avg", 20_000);
            assertEquals(2.5d, baseline.median(), 1e-9);
        }
    }

    @Test
    void sameGroupIncidentWindowsAreExcludedAndOthersAreNot() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            fixture.commit(0, "cpu.used.cores", 1);
            fixture.commit(5_000, "cpu.used.cores", 100);
            fixture.commit(10_000, "cpu.used.cores", 3);
            fixture.store.addIncidentWindow("probe-1", fixture.id(), "cpu", 5_000);
            Baseline same = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "cpu", "avg", 15_000);
            Baseline other = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "heap", "avg", 15_000);
            assertEquals(2, same.n());
            assertEquals(2d, same.median(), 1e-9);
            assertEquals(3, other.n());
            assertEquals(3d, other.median(), 1e-9);
        }
    }

    @Test
    void capCountsWindowsAndSkipsFlaggedOnesWithoutDroppingOlderWindows() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            for (int i = 0; i < 130; i++) {
                double value = i < 10 ? 1_000d : 10d;
                fixture.commit(i * 5_000L, "cpu.used.cores", value, true);
            }
            Baseline baseline = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "cpu", "avg", 130 * 5_000L);
            assertEquals(120, baseline.n());
            assertEquals(10d, baseline.median(), 1e-9);
        }
    }

    @Test
    void flaggedWindowIsSkippedAndAnOlderWindowFillsTheCap() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            for (int i = 0; i < 121; i++) {
                fixture.commit(i * 5_000L, "cpu.used.cores", i);
            }
            fixture.store.addIncidentWindow("probe-3", fixture.id(), "gc", 120 * 5_000L);
            Baseline baseline = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "gc", "avg", 121 * 5_000L);
            assertEquals(120, baseline.n());
            assertEquals(59.5d, baseline.median(), 1e-9);
        }
    }

    @Test
    void emptyWindowsStillConsumeASlot() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            for (int i = 0; i < 5; i++) {
                fixture.commit(i * 5_000L, "cpu.used.cores", 7);
            }
            for (int i = 5; i < 125; i++) {
                fixture.commitEmpty(i * 5_000L);
            }
            Baseline baseline = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "cpu", "avg", 125 * 5_000L);
            assertEquals(0, baseline.n());
            assertNull(baseline.median());
            assertTrue(baseline.flags().contains("unproven_baseline"));
        }
    }

    @Test
    void unprovenBaselineAndRawMad() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            double[] series = {1, 2, 3, 4, 5};
            for (int i = 0; i < series.length; i++) {
                fixture.commit(i * 5_000L, "cpu.used.cores", series[i]);
            }
            Baseline baseline = fixture.service().baseline(fixture.id(), "cpu.used.cores", "", "cpu", "avg", 5 * 5_000L);
            assertEquals(5, baseline.n());
            assertEquals(3d, baseline.median(), 1e-9);
            assertEquals(1d, baseline.mad(), 1e-9);
            assertTrue(baseline.flags().contains("unproven_baseline"));
            assertNotNull(baseline.median());
        }
    }

    @Test
    void mergedHistogramP99IsWithinTwoPercent() throws Exception {
        try (StoreFixture fixture = StoreFixture.open()) {
            fixture.commitPauses(0, 400, 250_000d);
            fixture.commitPauses(5_000, 400, 250_000d);
            Baseline baseline = fixture.service().baseline(fixture.id(), "gc.pause.us", "", "gc", "max", 10_000);
            assertNotNull(baseline.p99());
            assertEquals(250_000d, baseline.p99(), 250_000d * 0.02);
        }
    }

    private static final class StoreFixture implements AutoCloseable {
        final V2Store store;
        private int jvmId;
        private final Path root;
        private final BaselineService service;
        private int chunk;

        private StoreFixture(Path root, V2Store store, BaselineService service) {
            this.root = root;
            this.store = store;
            this.service = service;
        }

        static StoreFixture open() throws Exception {
            Path root = Files.createTempDirectory("jafra-baseline");
            V2Store store = new V2Store(root);
            Catalogue catalogue = Catalogue.load();
            return new StoreFixture(root, store, new BaselineService(store, catalogue));
        }

        int id() throws Exception {
            if (jvmId == 0) {
                jvmId = store.findJvms("ns", "pod", "app", "c").get(0).jvmId();
            }
            return jvmId;
        }

        BaselineService service() {
            return service;
        }

        void commit(long fromMs, String metric, double value) throws Exception {
            commit(fromMs, metric, value, false);
        }

        void commit(long fromMs, String metric, double value, boolean breakdown) throws Exception {
            WindowData data = blank(fromMs);
            data.add(metric, "", "", value);
            if (breakdown) {
                data.add(metric, "cause", "cause=other", 1_000d);
            }
            store.commit("c-" + chunk++, data);
        }

        void commitEmpty(long fromMs) throws Exception {
            store.commit("e-" + chunk++, blank(fromMs));
        }

        void commitPauses(long fromMs, int samples, double micros) throws Exception {
            WindowData data = blank(fromMs);
            for (int i = 0; i < samples; i++) {
                data.addHist("gc.pause.us", "", micros);
            }
            store.commit("p-" + chunk++, data);
        }

        private WindowData blank(long fromMs) {
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
            return data;
        }

        @Override
        public void close() throws Exception {
            store.close();
            delete(root);
        }

        private static void delete(Path root) throws Exception {
            if (root == null || !Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // temp dir cleanup is best-effort
                    }
                });
            }
        }
    }
}
