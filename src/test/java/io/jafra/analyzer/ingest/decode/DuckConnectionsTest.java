package io.jafra.analyzer.ingest.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DuckConnectionsTest {
    @Test
    void everyCatalogConnectionLimitsMemoryAndThreads() throws Exception {
        DuckConnections.configure("256MB", 1);
        Path v2 = Files.createTempDirectory("jafra-duck-limit").resolve("v2");
        try (IngestCatalog catalog = new IngestCatalog(v2);
                var statement = catalog.connection().createStatement();
                var rows = statement.executeQuery("SELECT current_setting('memory_limit'), current_setting('threads')")) {
            assertTrue(rows.next());
            String memory = rows.getString(1);
            String threads = rows.getString(2);
            double mebibytes = Double.parseDouble(memory.replace("MiB", "").trim());
            assertTrue(mebibytes <= 256 && mebibytes >= 240, memory);
            assertEquals("1", threads);
        }
    }
}
