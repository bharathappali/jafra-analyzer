package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class HourlyCompactionTest {
    @Test
    void mergesChunkFilesOlderThanOneHour() throws Exception {
        Path root = Files.createTempDirectory("jafra-compact");
        Path v2 = root.resolve("v2");
        long t = Instant.parse("2026-09-12T12:00:00Z").getEpochSecond() * 1_000_000_000L;
        try (IngestCatalog catalog = new IngestCatalog(v2)) {
            writeGauge(catalog, v2, t, "chunk-a");
            writeGauge(catalog, v2, t + 1_000_000L, "chunk-b");
            try (var files = Files.walk(v2.resolve("gauge"))) {
                files.filter(path -> path.getFileName().toString().endsWith(".parquet")).forEach(path -> {
                    try {
                        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
            int removed = HourlyCompaction.compact(catalog.connection(), v2, Instant.now(), Duration.ofHours(1));
            assertEquals(2, removed);
            assertEquals(2L, count(v2.resolve("gauge")));
            try (var hourFiles = Files.walk(v2.resolve("gauge"))) {
                long hours = hourFiles.filter(path -> path.getFileName().toString().startsWith("hour-")).count();
                assertEquals(1L, hours);
            }
        }
    }

    private static void writeGauge(IngestCatalog catalog, Path v2, long t, String chunkId) throws Exception {
        Path file = ParquetTables.partitionFile(v2, "gauge", "default", "pod", "app", "instance", t, chunkId);
        ParquetTables.write(catalog.connection(), file, "gauge",
                List.of("namespace", "pod", "container", "jvm_instance_id", "t", "metric", "value"),
                List.of(List.of("default", "pod", "app", "instance", t, "rss", 1.0)));
    }

    private static long count(Path table) throws Exception {
        String glob = table.toAbsolutePath().toString().replace("'", "''") + "/**/*.parquet";
        IngestCatalog.ensureDriver();
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM read_parquet('" + glob + "', hive_partitioning=false)")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
